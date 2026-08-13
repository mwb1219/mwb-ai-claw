package com.mwb.ai.claw.web;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.alibaba.cola.dto.SingleResponse;
import com.mwb.ai.claw.agent.executor.ChatCmdExe;
import com.mwb.ai.claw.api.AgentServiceI;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.CreateSessionCmd;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import com.mwb.ai.claw.dto.data.SessionDTO;

/**
 * Agent 对外 REST 接口：提供同步对话、会话管理与 SSE/WS 流式对话能力。
 */
@RestController
@RequestMapping("/agent")
@Profile("web")
public class AgentController {

    @Resource
    private AgentServiceI agentService;

    @Resource
    private ChatCmdExe chatCmdExe;

    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    /**
     * 同步对话
     */
    @PostMapping("/chat")
    public SingleResponse<ChatResponseDTO> chat(@RequestBody ChatCmd cmd) {
        return agentService.chat(cmd);
    }

    /**
     * 创建会话
     */
    @PostMapping("/session")
    public SingleResponse<SessionDTO> createSession(@RequestBody CreateSessionCmd cmd) {
        return agentService.createSession(cmd);
    }

    /**
     * 查询会话详情
     */
    @GetMapping("/session/{sessionId}")
    public SingleResponse<SessionDTO> getSession(@PathVariable String sessionId) {
        return agentService.getSession(sessionId);
    }

    /**
     * 列出所有会话
     */
    @GetMapping("/sessions")
    public SingleResponse<List<SessionDTO>> listSessions() {
        return agentService.listSessions();
    }

    /**
     * 删除指定会话
     */
    @DeleteMapping("/session/{sessionId}")
    public SingleResponse<Void> deleteSession(@PathVariable String sessionId) {
        return agentService.deleteSession(sessionId);
    }

    /**
     * SSE 流式对话：逐步推送推理轨迹与最终回复。
     * 事件类型：session / step / reply / done / error
     * <p>
     * 实现策略：
     * 1. 先获取/创建会话 → 立即推送 session 事件
     * 2. ReAct 循环通过 ProgressCallback 逐步推送 step 事件
     * 3. LLM 返回最终回复后推送 reply 事件
     * 4. 推送 done 事件结束
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam String message,
                                 @RequestParam(required = false) String sessionId,
                                 @RequestParam(required = false) String agentId) {
        SseEmitter emitter = new SseEmitter(120_000L);

        // 确保连接在完成/超时/错误时被正确关闭
        emitter.onCompletion(() -> {});
        emitter.onTimeout(() -> {});

        streamExecutor.execute(() -> {
            try {
                // === 阶段 1: 获取或创建会话，立即推送 session ===
                String effectiveSessionId = sessionId;
                if (effectiveSessionId == null || effectiveSessionId.trim().isEmpty()) {
                    CreateSessionCmd sessionCmd = new CreateSessionCmd();
                    if (agentId != null && !agentId.trim().isEmpty()) {
                        sessionCmd.setAgentId(agentId);
                    }
                    SingleResponse<SessionDTO> sessionResp = agentService.createSession(sessionCmd);
                    if (!sessionResp.isSuccess()) {
                        emitter.send(SseEmitter.event().name("error")
                                .data("创建会话失败: " + sessionResp.getErrMessage()));
                        emitter.send(SseEmitter.event().name("done").data(""));
                        emitter.complete();
                        return;
                    }
                    effectiveSessionId = sessionResp.getData().getSessionId();
                }
                emitter.send(SseEmitter.event().name("session").data(effectiveSessionId));

                // === 阶段 2: 执行 ReAct 循环，逐步推送 step + token ===
                ChatCmd cmd = new ChatCmd();
                cmd.setMessage(message);
                cmd.setSessionId(effectiveSessionId);
                cmd.setAgentId(agentId);

                // 进度回调：推送推理轨迹
                ProgressCallback callback = step -> {
                    try {
                        sendSseEvent(emitter, "step", step);
                    } catch (Exception ignored) {
                    }
                };

                // LLM 流式回调：推送 token 级增量
                LlmStreamCallback streamCallback = new LlmStreamCallback() {
                    @Override
                    public void onToken(String token) {
                        try {
                            sendSseEvent(emitter, "token", token);
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void onToolName(String toolName) {
                        try {
                            emitter.send(SseEmitter.event().name("tool_name").data(toolName));
                        } catch (Exception ignored) {
                        }
                    }

                    @Override
                    public void onToolArguments(String argDelta) {
                        try {
                            sendSseEvent(emitter, "tool_args", argDelta);
                        } catch (Exception ignored) {
                        }
                    }
                };

                SingleResponse<ChatResponseDTO> resp = chatCmdExe.execute(cmd, callback, streamCallback);
                ChatResponseDTO data = resp.getData();

                // === 阶段 3: 推送最终回复 ===
                if (data != null && data.getReply() != null) {
                    sendSseEvent(emitter, "reply", data.getReply());
                } else {
                    String errMsg = resp.getErrMessage() != null ? resp.getErrMessage() : "执行失败";
                    emitter.send(SseEmitter.event().name("error").data(errMsg));
                }

                // === 阶段 4: 结束 ===
                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("服务异常: " + e.getMessage()));
                    emitter.send(SseEmitter.event().name("done").data(""));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * SSE 发送多行数据：将 data 按 \n 拆分为多个 data: 行，
     * 保证含换行符的内容在 SSE 协议中正确传输。
     * <p>
     * 浏览器 EventSource 会自动将多行 data: 用 \n 拼接还原。
     */
    private static void sendSseEvent(SseEmitter emitter, String name, String data) throws IOException {
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(name);
        if (data == null || data.isEmpty()) {
            builder.data("");
        } else {
            // split with -1 limit 保留尾部空串，确保 data 末尾的 \n 不丢失
            for (String line : data.split("\n", -1)) {
                builder.data(line);
            }
        }
        emitter.send(builder);
    }

    @PreDestroy
    public void shutdown() {
        streamExecutor.shutdown();
    }
}
