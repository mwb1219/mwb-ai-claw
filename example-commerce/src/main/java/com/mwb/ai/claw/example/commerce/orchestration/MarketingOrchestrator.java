package com.mwb.ai.claw.example.commerce.orchestration;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.collaboration.model.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationDefinition;
import com.mwb.ai.claw.domain.collaboration.spi.AgentOrchestrator;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ReActResult;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.memory.gateway.LayeredMemoryGateway;
import com.mwb.ai.claw.infrastructure.collaboration.approval.ApprovalDecision;
import com.mwb.ai.claw.infrastructure.collaboration.approval.ApprovalRegistry;
import com.mwb.ai.claw.infrastructure.collaboration.approval.PendingApproval;
import com.mwb.ai.claw.infrastructure.collaboration.model.TodoDefinition;

/**
 * 自定义编排（type=marketing）：营销方案生成/对比编排插件。
 * <p>
 * 演示「编排类型可插拔扩展」：
 * <ol>
 *   <li>实现 {@link AgentOrchestrator} 并通过 {@link #type()} 声明唯一类型标识，
 *       注册为 Spring Bean 即被 {@code OrchestratorRegistry} 自动收集；</li>
 *   <li>在 orchestrations.json 增加一条 {@code type=marketing} 的定义即可被路由/显式选中；</li>
 *   <li>执行时复用框架 {@code ExecutionUnit}（会话 + ReAct + 记忆），agent 绑定业务工具；
 *       可选启用人工审批门禁（config.approvalEnabled=true）演示「生成方案后人工在环确认」。</li>
 * </ol>
 */
@Component
public class MarketingOrchestrator implements AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MarketingOrchestrator.class);

    private static final String TYPE = "marketing";
    /** 本编排默认使用的营销 Agent（优先于路由，可被 ctx.getExplicitAgentId() 覆盖） */
    private static final String DEFAULT_AGENT = "marketing";

    @Resource
    private LayeredMemoryGateway layeredMemoryGateway;

    @Resource
    private ApprovalRegistry approvalRegistry;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void validate(OrchestrationDefinition definition) {
        Object enabled = definition.getConfig().get("approvalEnabled");
        if (enabled != null && Boolean.parseBoolean(enabled.toString())) {
            // 审批门禁依赖会话 id 定位待审批节点
            if (definition.getConfig().get("approvalTimeoutMs") == null) {
                throw new IllegalArgumentException("marketing 编排开启 approvalEnabled 时应声明 approvalTimeoutMs");
            }
        }
    }

    @Override
    public CollaborationResult orchestrate(OrchestrationContext ctx) {
        Agent agent = resolveAgent(ctx);

        String sessionId = ctx.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return orchestrateLocked(ctx, agent);
        }
        return ctx.getExecutionUnit().executeWithSessionLock(ctx.getScope(), sessionId,
                () -> orchestrateLocked(ctx, agent));
    }

    private CollaborationResult orchestrateLocked(OrchestrationContext ctx, Agent agent) {
        Session session = ctx.getExecutionUnit().getOrCreateSession(ctx.getScope(), ctx.getSessionId(), agent);

        session.addUserMessage(ctx.getMessage(), ctx.getParts());
        ReActResult result = ctx.getExecutionUnit()
                .runSession(session, agent, ctx.getCallback(), ctx.getStreamCallback());

        if (result.getTraceSteps() != null && !result.getTraceSteps().isEmpty()) {
            session.getTraceSteps().addAll(result.getTraceSteps());
        }

        ctx.getExecutionUnit().saveSession(session);

        try {
            layeredMemoryGateway.afterSession(session, agent);
        } catch (Exception e) {
            // 提炼失败仅记录，不阻塞主链路
            log.warn("营销编排：记忆提炼失败，忽略", e);
        }

        String reply = buildReply(ctx, result, session);

        CollaborationResult cr = new CollaborationResult();
        cr.setReply(reply);
        cr.setSuccess(result.isSuccess());
        cr.setErrorMessage(result.getErrorMessage());
        cr.setAgentId(agent.getAgentId());
        cr.setSessionId(session.getSessionId());
        cr.setOrchestrationId(ctx.getDefinition().getId());
        cr.setTraceSteps(result.getTraceSteps());
        return cr;
    }

    /**
     * 组装最终回复：默认直接返回 Agent 生成的方案；若命中人工审批门禁，则生成后等待决策并标注状态。
     */
    private String buildReply(OrchestrationContext ctx, ReActResult result, Session session) {
        String base = result.getReply() == null ? "" : result.getReply();
        boolean approvalEnabled = readBool(ctx.getDefinition(), "approvalEnabled", false);
        if (!approvalEnabled) {
            return base;
        }
        if (ctx.getSessionId() == null || ctx.getSessionId().trim().isEmpty()) {
            // 无会话 id 无法定位审批节点，跳过门禁并在轨迹中提示
            ctx.getDefinition().getConfig().put("_skipped_approval", "缺少 sessionId");
            return base + "\n\n[提示] 开启审批门禁需携带 sessionId，本次未执行审批。";
        }

        List<TodoDefinition> plan = new ArrayList<>();
        TodoDefinition todo = new TodoDefinition();
        todo.setTodoId("marketing");
        todo.setTitle("确认并发布营销方案");
        todo.setDescription("对以上营销方案进行人工确认，批准后视为可执行。");
        plan.add(todo);

        PendingApproval pa = approvalRegistry.register(ctx.getScope(), ctx.getSessionId(), "root",
                "营销方案审批", plan);
        Long timeoutMs = readLong(ctx.getDefinition(), "approvalTimeoutMs", 0L);
        if (result.getTraceSteps() != null) {
            result.getTraceSteps().add("[Approval] 营销方案已生成，等待人工审批: " + session.getSessionId() + "/root");
        }
        ApprovalDecision decision = pa.await(timeoutMs);
        if (decision == ApprovalDecision.APPROVED) {
            return base + "\n\n[审批状态] ✅ 营销方案已获批准，可进入投放执行。";
        }
        if (decision == ApprovalDecision.TIMEOUT) {
            approvalRegistry.remove(pa);
        }
        return base + "\n\n[审批状态] ⏸ 营销方案等待/未获人工确认，暂缓投放（决策=" + decision + "）。";
    }

    private Agent resolveAgent(OrchestrationContext ctx) {
        AgentGateway agentGateway = ctx.getAgentGateway();
        if (ctx.getExplicitAgentId() != null && !ctx.getExplicitAgentId().trim().isEmpty()) {
            return agentGateway.getAgent(ctx.getExplicitAgentId());
        }
        try {
            return agentGateway.getAgent(DEFAULT_AGENT);
        } catch (Exception e) {
            return agentGateway.getAgent(null);
        }
    }

    private boolean readBool(OrchestrationDefinition def, String key, boolean defVal) {
        Object v = def.getConfig().get(key);
        return v == null ? defVal : Boolean.parseBoolean(v.toString());
    }

    private Long readLong(OrchestrationDefinition def, String key, Long defVal) {
        Object v = def.getConfig().get(key);
        if (v == null) {
            return defVal;
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return defVal;
        }
    }
}