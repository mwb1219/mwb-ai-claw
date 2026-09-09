package com.mwb.ai.claw.infrastructure.subagent;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.subagent.SubAgentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存态异步 spawn 注册表（H3 · Agent-as-Tool，切片 2）。
 * <p>
 * 以 {@code ConcurrentHashMap<agentId, SpawnedAgent>} 记录运行中的 spawn，配合
 * {@code spawn_subagent}（异步、立即返回 agentId）、{@code subagent_status}（轮询）、
 * {@code subagent_cancel}（手动取消）三个工具实现异步 spawn 生命周期管理。
 * <p>
 * 仅当 {@code agent.subagent.enabled=true} 且 {@code agent.subagent.async=true} 时装配（由
 * {@code ClawCoreAutoConfiguration} 注册）；默认关闭（async=false）时无此 Bean，系统零变化。
 * 为内存态、单实例架构（扁平多实例需自行扩展为分布式存储）。
 * <p>
 * 为防止长时间运行导致条目无限增长，注册新条目时会对「已终态且超过保留期」的旧条目做机会式清理
 * （不引入调度基础设施），保留期内已完成的条目仍可被『轮询到 final 结果』。
 */
public class SpawnedAgentRegistry {

    /** 终态条目保留时长（毫秒）：超过该时长且已结束的条目在下次注册时被清理 */
    private static final long RETAIN_MS = 10 * 60 * 1000L;

    private final Map<String, SpawnedAgent> agents = new ConcurrentHashMap<>();

    /** 注册一个运行中的 spawn */
    public SpawnedAgent register(String agentId, CompletableFuture<SubAgentResult> future,
                                 AgentScope scope, String name, String task) {
        pruneExpired();
        SpawnedAgent agent = new SpawnedAgent(agentId, future, scope, name, task);
        agents.put(agentId, agent);
        return agent;
    }

    /** 按 agentId 查询，不存在返回 null */
    public SpawnedAgent get(String agentId) {
        return agentId == null ? null : agents.get(agentId);
    }

    /** 是否注册了该 agentId */
    public boolean contains(String agentId) {
        return agentId != null && agents.containsKey(agentId);
    }

    /** 移除并返回指定条目，不存在返回 null */
    public SpawnedAgent remove(String agentId) {
        return agentId == null ? null : agents.remove(agentId);
    }

    /** 当前存活的注册条数（含已终态但未过期条目） */
    public int size() {
        return agents.size();
    }

    /** 返回当前注册的全部 spawn 快照（含已终态但未过期条目），供运维/可视化面板展示 */
    public List<SpawnedAgent> snapshot() {
        return new ArrayList<>(agents.values());
    }

    /** 返回由指定 scope（tenant/user）发起的 spawn 快照，保证跨租户/用户隔离 */
    public List<SpawnedAgent> snapshotBelongingTo(AgentScope scope) {
        List<SpawnedAgent> list = new ArrayList<>();
        for (SpawnedAgent agent : agents.values()) {
            if (agent.belongsTo(scope)) {
                list.add(agent);
            }
        }
        return list;
    }

    /** 清理超过保留期且已终态的条目（机会式，避免长期内存增长） */
    public void pruneExpired() {
        long now = System.currentTimeMillis();
        agents.entrySet().removeIf(e -> isTerminal(e.getValue()) && now - e.getValue().getCreatedAtMs() > RETAIN_MS);
    }

    private boolean isTerminal(SpawnedAgent agent) {
        CompletableFuture<SubAgentResult> future = agent.getFuture();
        return future.isDone() || future.isCancelled();
    }
}
