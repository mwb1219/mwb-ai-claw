package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.domain.subagent.SubAgentResult;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.domain.util.JsonUtils;
import com.mwb.ai.claw.infrastructure.subagent.SpawnedAgent;
import com.mwb.ai.claw.infrastructure.subagent.SpawnedAgentRegistry;
import com.mwb.ai.claw.infrastructure.tool.builtin.dto.SubagentRefParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 异步子代理状态查询工具（H3 · Agent-as-Tool，切片 2）。
 * <p>
 * 按 {@code agent_id} 查询一个已 spawn 子代理的当前状态（running / done / cancelled / not_found），
 * 用于轮询后台执行结果：done 时携带完整 {@link SubAgentResult}（reply / traceSteps / tokens 等）。
 * 仅当 {@code agent.subagent.enabled=true} 且 {@code agent.subagent.async=true} 时装配。
 * <p>
 * 租户隔离：仅允许查询由本 scope（tenant/user）发起的 spawn。
 */
@Component
@ConditionalOnProperty(prefix = "agent.subagent", name = {"enabled", "async"}, havingValue = "true")
public class SubAgentStatusTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubAgentStatusTool.class);
    private static final String NAME = "subagent_status";
    private static final String PARAMS_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"agent_id\":{\"type\":\"string\",\"description\":\"spawn_subagent 返回的子代理 id（必填）\"}"
            + "},"
            + "\"required\":[\"agent_id\"]"
            + "}";

    private final SpawnedAgentRegistry registry;

    public SubAgentStatusTool(SpawnedAgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        ToolSpec spec = new ToolSpec(NAME,
                "按 agent_id 查询一个异步 spawn 子代理的状态：running / done / cancelled / not_found；"
                        + "done 时携带子代理的完整执行结果。",
                PARAMS_SCHEMA);
        spec.setGlobal(true);
        return spec;
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        try {
            SubagentRefParams params = JsonUtils.fromJson(argumentsJson == null ? "{}" : argumentsJson,
                    SubagentRefParams.class);
            if (params.getAgentId() == null || params.getAgentId().trim().isEmpty()) {
                return ToolResult.error("subagent_status 缺少必填参数 agent_id");
            }
            SpawnedAgent agent = registry.get(params.getAgentId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("agent_id", params.getAgentId());
            if (agent == null) {
                out.put("status", "not_found");
                return ToolResult.success(JsonUtils.toJson(out));
            }
            AgentScope scope = AgentScopeContext.get();
            if (!agent.belongsTo(scope)) {
                return ToolResult.error("当前 scope 无权查询该子代理（agent_id=" + params.getAgentId() + "）");
            }
            out.put("name", agent.getName());
            out.put("task", agent.getTask());
            out.put("created_at_ms", agent.getCreatedAtMs());

            CompletableFuture<SubAgentResult> future = agent.getFuture();
            if (future.isCancelled()) {
                out.put("status", "cancelled");
            } else if (future.isDone()) {
                out.put("status", "done");
                out.put("subagent", future.getNow(null));
            } else {
                out.put("status", "running");
            }
            return ToolResult.success(JsonUtils.toJson(out));
        } catch (Exception e) {
            log.error("subagent_status 执行失败", e);
            return ToolResult.error("subagent_status 执行失败: " + e.getMessage());
        }
    }
}
