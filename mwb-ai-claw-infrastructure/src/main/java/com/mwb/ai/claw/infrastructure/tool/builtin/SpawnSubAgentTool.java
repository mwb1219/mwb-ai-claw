package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.domain.subagent.SubAgentSpec;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.domain.util.JsonUtils;
import com.mwb.ai.claw.infrastructure.subagent.SpawnedAgentRegistry;
import com.mwb.ai.claw.infrastructure.subagent.SubAgentExecutor;
import com.mwb.ai.claw.infrastructure.tool.builtin.dto.SpawnAgentParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异步 spawn 子代理工具（H3 · Agent-as-Tool，切片 2）。
 * <p>
 * 与同步 {@code spawn_agent} 的区别：调用后**立即返回 agentId**，子代理在后台线程执行，主 Agent 可继续推进；
 * 随后用 {@code subagent_status} 轮询结果、{@code subagent_cancel} 手动取消。仅当
 * {@code agent.subagent.enabled=true} 且 {@code agent.subagent.async=true} 时装配（默认关闭，系统零变化）。
 * <p>
 * 执行流程：解析参数 → 鉴权（isAllowed / validate）→ {@link SubAgentExecutor#spawnAsync} 构建 Agent 并提交后台执行 →
 * 将 agentId / future / scope 注册到 {@link SpawnedAgentRegistry}，返回结构化 agentId。
 */
@Component
@ConditionalOnProperty(prefix = "agent.subagent", name = {"enabled", "async"}, havingValue = "true")
public class SpawnSubAgentTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(SpawnSubAgentTool.class);
    private static final String NAME = "spawn_subagent";
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

    // 用 ObjectProvider 懒解析 SubAgentExecutor：它依赖 ExecutionUnit（toolGateway 所在链），而本工具又被
    // toolGateway 的 List<ToolExecutor> 收集，直接构造注入会与 executionUnit→reActLoopService→toolGateway 形成循环依赖。
    private final ObjectProvider<SubAgentExecutor> subAgentExecutorProvider;
    private final SpawnedAgentRegistry registry;

    public SpawnSubAgentTool(ObjectProvider<SubAgentExecutor> subAgentExecutorProvider, SpawnedAgentRegistry registry) {
        this.subAgentExecutorProvider = subAgentExecutorProvider;
        this.registry = registry;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        ToolSpec spec = new ToolSpec(NAME,
                "异步生成一个临时子代理去完成单个任务，立即返回 agent_id，随后可用 subagent_status 轮询、"
                        + "subagent_cancel 取消。适用于长任务、可并行子任务或需要手动控制生命周期的场景。",
                PARAMS_SCHEMA);
        spec.setGlobal(true);
        return spec;
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        try {
            SpawnAgentParams params = JsonUtils.fromJson(argumentsJson == null ? "{}" : argumentsJson,
                    SpawnAgentParams.class);
            if (params.getTask() == null || params.getTask().trim().isEmpty()) {
                return ToolResult.error("spawn_subagent 缺少必填参数 task");
            }
            AgentScope scope = AgentScopeContext.get(); // 必须继承调用方租户/用户
            SubAgentExecutor subAgentExecutor = subAgentExecutorProvider.getIfAvailable();
            if (subAgentExecutor == null) {
                return ToolResult.error("子代理执行器未装配（agent.subagent 未启用）");
            }
            if (!subAgentExecutor.isAllowed(scope)) {
                return ToolResult.error("当前 scope 不允许生成子代理（sub-agent 未开启或租户不在白名单）");
            }
            SubAgentSpec spec = buildSpec(params);
            subAgentExecutor.validate(spec);
            SubAgentExecutor.AsyncSpawn spawn = subAgentExecutor.spawnAsync(spec, scope);
            if (spawn.isRefused()) {
                return ToolResult.error(spawn.getRefused().getError());
            }
            registry.register(spawn.getAgentId(), spawn.getFuture(), scope, spec.getName(), spec.getTask());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("agent_id", spawn.getAgentId());
            out.put("name", spec.getName());
            out.put("task", spec.getTask());
            out.put("status", "running");
            return ToolResult.success(JsonUtils.toJson(out));
        } catch (Exception e) {
            log.error("spawn_subagent 执行失败", e);
            return ToolResult.error("spawn_subagent 执行失败: " + e.getMessage());
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
