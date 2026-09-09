package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.domain.subagent.SubAgentResult;
import com.mwb.ai.claw.domain.subagent.SubAgentSpec;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.domain.util.JsonUtils;
import com.mwb.ai.claw.infrastructure.subagent.SubAgentExecutor;
import com.mwb.ai.claw.infrastructure.tool.builtin.dto.SpawnAgentParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 子代理动态生成工具（H3 · Agent-as-Tool）：主 Agent 在 ReAct 中动态创建并驱动一个临时子代理完成单个任务，
 * 结果以结构化 {@link SubAgentResult}（JSON）作为工具 Observation 回传。
 * <p>
 * 以 {@code global=true} 注册，对**所有** Agent 可见，无需在 agents.json / application.yml 显式声明；
 * 仅在 {@code agent.subagent.enabled=true} 时装配（{@code @ConditionalOnProperty}），默认关闭时系统零变化。
 * <p>
 * 本类为**同步入口**：核心执行逻辑（构建 Agent → 临时会话 ReAct → 组装结果 → 深度配额 / 预算 / 超时兜底）
 * 已抽取到 {@link SubAgentExecutor}，本类仅负责参数解析、鉴权前置与结果 JSON 化。
 */
@Component
@ConditionalOnProperty(name = "agent.subagent.enabled", havingValue = "true")
public class SpawnAgentTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(SpawnAgentTool.class);
    private static final String NAME = "spawn_agent";
    private static final String PARAMS_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"task\":{\"type\":\"string\",\"description\":\"交给子代理的任务描述（必填）\"},"
            + "\"name\":{\"type\":\"string\",\"description\":\"子代理展示名（可选）\"},"
            + "\"instructions\":{\"type\":\"string\",\"description\":\"追加到 system prompt 的专属指令/人设（可选）\"},"
            + "\"model\":{\"type\":\"string\",\"description\":\"模型覆盖（可选，缺省继承默认/调用方模型）\"},"
            + "\"provider\":{\"type\":\"string\",\"description\":\"模型提供方覆盖（可选）\"},"
            + "\"max_steps\":{\"type\":\"integer\",\"description\":\"推理步数上限（可选，正数生效）\"},"
            + "\"max_tokens\":{\"type\":\"integer\",\"description\":\"单次最大 tokens（可选，正数生效）\"},"
            + "\"tools\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"允许的工具名列表（可选，空=绑定全部）\"},"
            + "\"memory\":{\"type\":\"boolean\",\"description\":\"是否允许读写分层记忆（默认 true）\"}"
            + "},"
            + "\"required\":[\"task\"]"
            + "}";

    // 用 ObjectProvider 懒解析 SubAgentExecutor：它依赖 ExecutionUnit（toolGateway 所在链），而工具本身又被
    // toolGateway 的 List<ToolExecutor> 收集，直接构造注入会与 executionUnit→reActLoopService→toolGateway 形成循环依赖。
    private final ObjectProvider<SubAgentExecutor> subAgentExecutorProvider;

    public SpawnAgentTool(ObjectProvider<SubAgentExecutor> subAgentExecutorProvider) {
        this.subAgentExecutorProvider = subAgentExecutorProvider;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        ToolSpec spec = new ToolSpec(NAME,
                "按需生成一个临时子代理去完成单个任务，并返回其执行结果。当任务需要独立模型/专属人设/独立预算的"
                        + "专职代理时使用；子任务结果可直接作为Observation。",
                PARAMS_SCHEMA);
        spec.setGlobal(true);
        return spec;
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        try {
            SpawnAgentParams params = JsonUtils.fromJson(argumentsJson == null ? "{}" : argumentsJson,
                    SpawnAgentParams.class);
            SubAgentSpec spec = buildSpec(params);
            if (spec.getTask() == null || spec.getTask().trim().isEmpty()) {
                return ToolResult.error("spawn_agent 缺少必填参数 task");
            }
            AgentScope scope = AgentScopeContext.get(); // 必须继承调用方租户/用户
            SubAgentExecutor subAgentExecutor = subAgentExecutorProvider.getIfAvailable();
            if (subAgentExecutor == null) {
                return ToolResult.error("子代理执行器未装配（agent.subagent 未启用）");
            }
            if (!subAgentExecutor.isAllowed(scope)) {
                return ToolResult.error("当前 scope 不允许生成子代理（sub-agent 未开启或租户不在白名单）");
            }
            subAgentExecutor.validate(spec);
            SubAgentResult result = subAgentExecutor.runSync(spec, scope);
            return ToolResult.success(JsonUtils.toJson(result));
        } catch (Exception e) {
            log.error("spawn_agent 执行失败", e);
            return ToolResult.error("spawn_agent 执行失败: " + e.getMessage());
        }
    }

    private SubAgentSpec buildSpec(SpawnAgentParams params) {
        SubAgentSpec spec = new SubAgentSpec();
        spec.setTask(params.getTask());
        spec.setName(params.getName());
        spec.setInstructions(params.getInstructions());
        spec.setModel(params.getModel());
        spec.setProvider(params.getProvider());
        spec.setMaxSteps(params.getMaxSteps());
        spec.setMaxTokens(params.getMaxTokens());
        spec.setTools(params.getTools());
        spec.setMemory(params.isMemory());
        return spec;
    }
}
