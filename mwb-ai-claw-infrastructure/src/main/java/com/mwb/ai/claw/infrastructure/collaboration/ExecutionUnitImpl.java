package com.mwb.ai.claw.infrastructure.collaboration;

import com.mwb.ai.claw.domain.collaboration.ExecutionUnit;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.core.ReActLoopService;
import com.mwb.ai.claw.domain.core.ReActResult;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.memory.MemoryGateway;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.alibaba.cola.exception.BizException;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 公共执行单元实现：封装主会话 / 临时会话执行与产物落盘。
 */
@Component
public class ExecutionUnitImpl implements ExecutionUnit {

    @Resource
    private MemoryGateway memoryGateway;

    @Resource
    private ReActLoopService reActLoopService;

    @Override
    public Session getOrCreateSession(String sessionId, Agent agent) {
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            Session session = memoryGateway.getSession(sessionId);
            if (session == null) {
                throw new BizException(AgentErrorCode.B_AGENT_SESSION_NOT_FOUND.getErrCode(),
                        "会话不存在: " + sessionId);
            }
            return session;
        }
        Session session = new Session();
        session.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        session.setAgentId(agent.getAgentId());
        session.setTitle("session-" + System.currentTimeMillis());
        return session;
    }

    @Override
    public void saveSession(Session session) {
        memoryGateway.saveSession(session);
    }

    @Override
    public ReActResult runSession(Session session, Agent agent,
                                  ProgressCallback callback, LlmStreamCallback streamCallback) {
        if (streamCallback != null) {
            return reActLoopService.streamRun(session, agent, callback, streamCallback);
        }
        return reActLoopService.run(session, agent, callback);
    }

    @Override
    public String runAgent(String prompt, Agent agent, ProgressCallback callback) {
        // 临时会话：不入库，仅作为 ReAct 执行载体（阶段/参与者之间上下文隔离）
        Session session = new Session();
        session.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        session.setAgentId(agent.getAgentId());
        session.addUserMessage(prompt);
        return reActLoopService.run(session, agent, callback).getReply();
    }

    @Override
    public Path writeArtifact(String workdir, String stageId, String content) {
        try {
            Path dir = Paths.get(workdir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve(stageId + ".md");
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException e) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "流水线产物落盘失败: " + stageId + " - " + e.getMessage());
        }
    }
}
