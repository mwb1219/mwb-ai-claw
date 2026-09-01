package com.mwb.ai.claw.infrastructure.collaboration.routing;

import com.mwb.ai.claw.domain.collaboration.model.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.spi.AgentOrchestrator;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.collaboration.spi.AgentRouter;
import com.mwb.ai.claw.domain.core.ReActResult;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.memory.layered.LayeredMemoryGateway;
import com.mwb.ai.claw.infrastructure.memory.longterm.LongTermMemoryWriter;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 专家路由编排（内置插件，type=routing）：
 * 迁入原 ChatCmdExe 的「路由选 Agent → 会话 → ReAct → 持久化 → 分层记忆提炼」逻辑，行为保持不变。
 */
@Component
public class RoutingOrchestrator implements AgentOrchestrator {

    @Resource
    private AgentRouter agentRouter;

    @Resource
    private LayeredMemoryGateway layeredMemoryGateway;

    @Resource
    private LongTermMemoryWriter longTermMemoryWriter;

    @Override
    public String type() {
        return "routing";
    }

    @Override
    public CollaborationResult orchestrate(OrchestrationContext ctx) {
        Agent agent = resolveAgent(ctx);

        // 主会话粒度加锁：同会话「获取 → 追加 → 推理 → 保存 → 提炼」串行化，不同会话/用户完全并行；
        // 未携带 sessionId（临时/嵌套编排，不入库无持久化竞争）不加锁。
        String sessionId = ctx.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return orchestrateLocked(ctx, agent);
        }
        return ctx.getExecutionUnit().executeWithSessionLock(ctx.getScope(), sessionId,
                () -> orchestrateLocked(ctx, agent));
    }

    private CollaborationResult orchestrateLocked(OrchestrationContext ctx, Agent agent) {
        // 获取或创建会话
        Session session = ctx.getExecutionUnit().getOrCreateSession(ctx.getScope(), ctx.getSessionId(), agent);

        // 追加用户消息（D2 多模态：parts 非空时携带图片片段）并执行 ReAct
        session.addUserMessage(ctx.getMessage(), ctx.getParts());

        // T8-A 关键字触发入口（异步，不阻塞）：命中「我叫/我是/记住我」等身份/风格声明即写入 MEMORY.md
        longTermMemoryWriter.captureAsync(ctx.getScope(), ctx.getMessage());

        ReActResult result = ctx.getExecutionUnit()
                .runSession(session, agent, ctx.getCallback(), ctx.getStreamCallback());

        // 累加本轮推理轨迹到会话：刷新后前端可从会话详情恢复展示轨迹与工具调用
        if (result.getTraceSteps() != null && !result.getTraceSteps().isEmpty()) {
            session.getTraceSteps().addAll(result.getTraceSteps());
        }

        // 持久化会话
        ctx.getExecutionUnit().saveSession(session);

        // 分层记忆：会话结束提炼（失败不影响响应）
        try {
            layeredMemoryGateway.afterSession(session, agent);
        } catch (Exception e) {
            // 提炼失败仅记录，不阻塞主链路
        }

        CollaborationResult cr = new CollaborationResult();
        cr.setReply(result.getReply());
        cr.setSuccess(result.isSuccess());
        cr.setErrorMessage(result.getErrorMessage());
        cr.setErrorCategory(result.getErrorCategory());
        cr.setAgentId(agent.getAgentId());
        cr.setSessionId(session.getSessionId());
        cr.setOrchestrationId(ctx.getDefinition().getId());
        cr.setTraceSteps(result.getTraceSteps());
        return cr;
    }

    /**
     * 解析目标 Agent：显式指定 agentId 优先，否则通过路由决策，路由未命中回退默认 Agent。
     */
    private Agent resolveAgent(OrchestrationContext ctx) {
        AgentGateway agentGateway = ctx.getAgentGateway();
        if (ctx.getExplicitAgentId() != null && !ctx.getExplicitAgentId().trim().isEmpty()) {
            return agentGateway.getAgent(ctx.getExplicitAgentId());
        }
        String routedAgentId = agentRouter.route(ctx.getMessage());
        if (routedAgentId != null && !routedAgentId.trim().isEmpty()) {
            return agentGateway.getAgent(routedAgentId);
        }
        return agentGateway.getAgent(null);
    }
}
