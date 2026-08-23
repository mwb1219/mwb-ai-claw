package com.mwb.ai.claw.infrastructure.collaboration.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Resource;

import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.collaboration.model.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationDefinition;
import com.mwb.ai.claw.domain.collaboration.spi.AgentOrchestrator;
import com.mwb.ai.claw.domain.collaboration.spi.ExecutionUnit;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.core.ReActLoopService;
import com.mwb.ai.claw.domain.core.ReActResult;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.memory.gateway.MemoryGateway;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.infrastructure.collaboration.lock.SessionLockManager;
import com.mwb.ai.claw.infrastructure.collaboration.registry.OrchestratorRegistry;
import com.mwb.ai.claw.infrastructure.config.OrchestrationConfigLoader;

/**
 * 公共执行单元实现：封装主会话 / 临时会话执行与产物落盘。
 */
@Component
public class ExecutionUnitImpl implements ExecutionUnit {

    @Resource
    private MemoryGateway memoryGateway;

    @Resource
    private ReActLoopService reActLoopService;

    @Resource
    private AgentGateway agentGateway;

    @Resource
    private OrchestrationConfigLoader orchestrationLoader;

    @Resource
    private OrchestratorRegistry orchestratorRegistry;

    @Resource
    private SessionLockManager sessionLockManager;

    @Override
    public Session getOrCreateSession(AgentScope scope, String sessionId, Agent agent) {
        AgentScope effectiveScope = scope != null ? scope : AgentScope.defaultScope();
        if (sessionId != null && !sessionId.trim().isEmpty()) {
            Session session = memoryGateway.getSession(effectiveScope, sessionId);
            if (session == null) {
                throw new BizException(AgentErrorCode.B_AGENT_SESSION_NOT_FOUND.getErrCode(),
                        "会话不存在: " + sessionId);
            }
            return session;
        }
        Session session = new Session();
        session.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        session.setAgentId(agent.getAgentId());
        session.setTenantId(effectiveScope.getTenantId());
        session.setUserId(effectiveScope.getUserId());
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
    public String runAgent(String prompt, Agent agent, ProgressCallback callback,
                           LlmStreamCallback streamCallback) {
        // 临时会话：不入库，仅作为 ReAct 执行载体（阶段/参与者之间上下文隔离）
        Session session = new Session();
        session.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        session.setAgentId(agent.getAgentId());
        session.addUserMessage(prompt);
        if (streamCallback != null) {
            return reActLoopService.streamRun(session, agent, callback, streamCallback).getReply();
        }
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

    @Override
    public Path writeFile(String dir, String fileName, String content) {
        try {
            Path directory = Paths.get(dir).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path file = directory.resolve(fileName);
            Files.write(file, content.getBytes(StandardCharsets.UTF_8));
            return file;
        } catch (IOException e) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "编排产物落盘失败: " + fileName + " - " + e.getMessage());
        }
    }

    @Override
    public CollaborationResult runOrchestration(AgentScope scope, String message, String orchestrationId) {
        return runOrchestration(scope, message, orchestrationId, null);
    }

    @Override
    public CollaborationResult runOrchestration(AgentScope scope, String message, String orchestrationId,
                                                ProgressCallback callback) {
        OrchestrationDefinition definition = orchestrationLoader.get(orchestrationId);
        AgentOrchestrator orchestrator = orchestratorRegistry.resolve(definition);
        // 嵌套上下文：复用全局 Agent 注册表 / 执行单元，独立消息与会话（嵌套编排内部自建临时会话与轨迹）
        OrchestrationContext ctx = new OrchestrationContext();
        ctx.setScope(scope != null ? scope : AgentScope.defaultScope());
        ctx.setMessage(message);
        ctx.setDefinition(definition);
        ctx.setAgentGateway(agentGateway);
        ctx.setExecutionUnit(this);
        ctx.setCallback(callback);
        return orchestrator.orchestrate(ctx);
    }

    @Override
    public void executeWithSessionLock(AgentScope scope, String sessionId, Runnable task) {
        sessionLockManager.executeWithLock(scope, sessionId, task);
    }

    @Override
    public <T> T executeWithSessionLock(AgentScope scope, String sessionId, Supplier<T> task) {
        return sessionLockManager.executeWithLock(scope, sessionId, task);
    }
}
