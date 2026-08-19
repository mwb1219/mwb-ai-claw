package com.mwb.ai.claw.web;

import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.agent.ApprovalService;
import com.mwb.ai.claw.agent.executor.ChatCmdExe;
import com.mwb.ai.claw.api.AgentServiceI;
import com.mwb.ai.claw.dto.ApprovalCmd;
import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.CreateSessionCmd;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import com.mwb.ai.claw.dto.data.PendingApprovalDTO;
import com.mwb.ai.claw.dto.data.SessionDTO;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import com.mwb.ai.claw.web.dto.WsEvent;
import com.mwb.ai.claw.web.dto.WsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent WebSocket 处理器：接收 JSON 消息，执行 ReAct 循环并通过 WebSocket 推送流式结果。
 *
 * <h3>协议说明</h3>
 * <pre>
 * 客户端发送：
 *   {"type":"chat","message":"你好","sessionId":"xxx","agentId":"yyy"}
 *
 * 服务端推送事件（JSON Lines）：
 *   {"type":"session","data":"sessionId"}
 *   {"type":"step","data":"推理轨迹..."}
 *   {"type":"token","data":"增量文本"}
 *   {"type":"tool_name","data":"工具名"}
 *   {"type":"tool_args","data":"工具参数增量"}
 *   {"type":"reply","data":"最终回复"}
 *   {"type":"done"}
 *   {"type":"error","data":"错误信息"}
 * </pre>
 */
@Component
@Profile("web")
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentWebSocketHandler.class);

    @Resource
    private AgentServiceI agentService;

    @Resource
    private ChatCmdExe chatCmdExe;

    @Resource
    private ApprovalService approvalService;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket 连接建立: id={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        log.info("WebSocket 收到消息: id={}, payload={}", session.getId(),
                payload.length() > 300 ? payload.substring(0, 300) + "..." : payload);

        executor.execute(() -> {
            try {
                WsRequest req = JsonUtils.fromJson(payload, WsRequest.class);
                String type = req.getType() != null ? req.getType() : "chat";

                switch (type) {
                    case "chat":
                        handleChat(session, req);
                        break;
                    case "approve":
                    case "reject":
                        handleApproval(session, req, type);
                        break;
                    case "pending_tasks":
                        handlePendingTasks(session, req);
                        break;
                    default:
                        sendEvent(session, "error", "不支持的消息类型: " + type);
                        sendEvent(session, "done", null);
                }
            } catch (Exception e) {
                log.error("WebSocket 消息处理异常: id={}, err={}", session.getId(), e.getMessage(), e);
                try {
                    sendEvent(session, "error", "消息处理异常: " + e.getMessage());
                    sendEvent(session, "done", null);
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * 人工审批（approve / reject）：将决策写入待审批注册表，唤醒等待中的编排线程继续。
     */
    private void handleApproval(WebSocketSession session, WsRequest req, String type) throws IOException {
        ApprovalCmd cmd = new ApprovalCmd();
        cmd.setSessionId(req.getSessionId());
        cmd.setLayerKey(req.getLayerKey());
        SingleResponse<Void> resp = "approve".equals(type)
                ? approvalService.approve(cmd) : approvalService.reject(cmd);
        if (resp.isSuccess()) {
            sendEvent(session, "approval", ("approve".equals(type) ? "已批准" : "已拒绝") + ": " + req.getLayerKey());
        } else {
            sendEvent(session, "error", resp.getErrMessage());
        }
        sendEvent(session, "done", null);
    }

    /**
     * 待审批节点列表：以 JSON 文本返回 [{sessionId, layerKey, task, todoCount, createdAt}...]。
     */
    private void handlePendingTasks(WebSocketSession session, WsRequest req) throws IOException {
        SingleResponse<List<PendingApprovalDTO>> resp = approvalService.pendingTasks(req.getSessionId());
        sendEvent(session, "approval", JsonUtils.toJson(resp.getData()));
        sendEvent(session, "done", null);
    }

    /**
     * 处理聊天消息，执行完整的 ReAct 流式对话流程。
     */
    private void handleChat(WebSocketSession session, WsRequest req) throws Exception {
        String message = req.getMessage();
        String sessionId = req.getSessionId();
        String agentId = req.getAgentId();

        if (message == null || message.trim().isEmpty()) {
            sendEvent(session, "error", "消息内容不能为空");
            sendEvent(session, "done", null);
            return;
        }

        // === 阶段 1: 获取或创建会话 ===
        String effectiveSessionId = sessionId;
        if (effectiveSessionId == null || effectiveSessionId.trim().isEmpty()) {
            CreateSessionCmd sessionCmd = new CreateSessionCmd();
            if (agentId != null && !agentId.trim().isEmpty()) {
                sessionCmd.setAgentId(agentId);
            }
            SingleResponse<SessionDTO> sessionResp = agentService.createSession(sessionCmd);
            if (!sessionResp.isSuccess()) {
                sendEvent(session, "error", "创建会话失败: " + sessionResp.getErrMessage());
                sendEvent(session, "done", null);
                return;
            }
            effectiveSessionId = sessionResp.getData().getSessionId();
        }
        sendEvent(session, "session", effectiveSessionId);

        // === 阶段 2: 执行 ReAct 循环 ===
        ChatCmd cmd = new ChatCmd();
        cmd.setMessage(message);
        cmd.setSessionId(effectiveSessionId);
        cmd.setAgentId(agentId);

        // 进度回调：推送推理轨迹
        ProgressCallback callback = step -> {
            try {
                sendEvent(session, "step", step);
            } catch (Exception ignored) {}
        };

        // LLM 流式回调：推送 token 增量
        LlmStreamCallback streamCallback = new LlmStreamCallback() {
            @Override
            public void onToken(String token) {
                try {
                    sendEvent(session, "token", token);
                } catch (Exception ignored) {}
            }

            @Override
            public void onToolName(String toolName) {
                try {
                    sendEvent(session, "tool_name", toolName);
                } catch (Exception ignored) {}
            }

            @Override
            public void onToolArguments(String argDelta) {
                try {
                    sendEvent(session, "tool_args", argDelta);
                } catch (Exception ignored) {}
            }
        };

        SingleResponse<ChatResponseDTO> resp = chatCmdExe.execute(cmd, callback, streamCallback);
        ChatResponseDTO data = resp.getData();

        // === 阶段 3: 推送最终回复 ===
        if (data != null && data.getReply() != null) {
            sendEvent(session, "reply", data.getReply());
        } else {
            String errMsg = resp.getErrMessage() != null ? resp.getErrMessage() : "执行失败";
            sendEvent(session, "error", errMsg);
        }

        // === 阶段 4: 结束 ===
        sendEvent(session, "done", null);
    }

    /**
     * 向 WebSocket 会话发送 JSON 事件。
     */
    private void sendEvent(WebSocketSession session, String type, String data) throws IOException {
        if (!session.isOpen()) {
            return;
        }
        String json = JsonUtils.toJson(new WsEvent(type, data));
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket 连接关闭: id={}, status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket 传输异常: id={}, err={}", session.getId(), exception.getMessage());
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
