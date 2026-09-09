package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
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
 * 异步子代理取消工具（H3 · Agent-as-Tool，切片 2）。
 * <p>
 * 按 {@code agent_id} 手动取消一个运行中的 spawn（中断其后台执行线程）；已终态（done / cancelled）时取消失败，
 * 返回现状。仅当 {@code agent.subagent.enabled=true} 且 {@code agent.subagent.async=true} 时装配。
 * <p>
 * 租户隔离：仅允许取消由本 scope（tenant/user）发起的 spawn。取消后条目仍保留在注册表，可用
 * {@code subagent_status} 轮询到终态（超保留期后由机会式清理回收）。
 */
@Component
@ConditionalOnProperty(prefix = "agent.subagent", name = {"enabled", "async"}, havingValue = "true")
public class SubAgentCancelTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubAgentCancelTool.class);
    private static final String NAME = "subagent_cancel";
    private static final String PARAMS_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"agent_id\":{\"type\":\"string\",\"description\":\"spawn_subagent 返回的子代理 id（必填）\"}"
            + "},"
            + "\"required\":[\"agent_id\"]"
            + "}";

    private final SpawnedAgentRegistry registry;

    public SubAgentCancelTool(SpawnedAgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        ToolSpec spec = new ToolSpec(NAME,
                "按 agent_id 手动取消一个正在运行的异步子代理（中断其后台执行）；已终态时取消失败并返回现状。",
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
                return ToolResult.error("subagent_cancel 缺少必填参数 agent_id");
            }
            SpawnedAgent agent = registry.get(params.getAgentId());
            if (agent == null) {
                return ToolResult.error("未找到该子代理（agent_id=" + params.getAgentId() + "）");
            }
            AgentScope scope = AgentScopeContext.get();
            if (!agent.belongsTo(scope)) {
                return ToolResult.error("当前 scope 无权取消该子代理（agent_id=" + params.getAgentId() + "）");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("agent_id", params.getAgentId());
            CompletableFuture<?> future = agent.getFuture();
            boolean cancelled = future.cancel(true);
            out.put("cancelled", cancelled);
            if (!cancelled) {
                out.put("status", future.isDone() ? "done" : "cancelled");
            }
            return ToolResult.success(JsonUtils.toJson(out));
        } catch (Exception e) {
            log.error("subagent_cancel 执行失败", e);
            return ToolResult.error("subagent_cancel 执行失败: " + e.getMessage());
        }
    }
}
