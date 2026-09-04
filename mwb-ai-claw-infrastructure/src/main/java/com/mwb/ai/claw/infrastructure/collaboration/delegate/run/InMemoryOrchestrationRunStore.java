package com.mwb.ai.claw.infrastructure.collaboration.delegate.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 内存版委托编排运行记录存储（{@code agent.collaboration.orchestration-run.store=local}）。
 * <p>
 * 单 JVM 内的 {@code ConcurrentHashMap}，支持跨请求（同实例）续跑；实例重启即丢失。
 * key 使用 scope keyPrefix + runId 隔离租户/用户维度。并发续跑认领基于单条记录对象加锁做 CAS。
 */
public class InMemoryOrchestrationRunStore implements OrchestrationRunStore {

    private final ConcurrentMap<String, OrchestrationRun> runs = new ConcurrentHashMap<>();

    @Override
    public void create(OrchestrationRun run) {
        if (run == null || run.getRunId() == null) {
            return;
        }
        if (run.getCreateTime() == 0) {
            run.setCreateTime(System.currentTimeMillis());
        }
        run.setUpdateTime(System.currentTimeMillis());
        runs.put(key(run.getTenantId(), run.getUserId(), run.getRunId()), run);
    }

    @Override
    public void update(OrchestrationRun run) {
        if (run == null || run.getRunId() == null) {
            return;
        }
        run.setVersion(run.getVersion() + 1);
        run.setUpdateTime(System.currentTimeMillis());
        runs.put(key(run.getTenantId(), run.getUserId(), run.getRunId()), run);
    }

    @Override
    public Optional<OrchestrationRun> get(AgentScope scope, String runId) {
        if (runId == null || runId.isEmpty()) {
            return Optional.empty();
        }
        String t = nz(scope == null ? "" : scope.getTenantId());
        String u = nz(scope == null ? "" : scope.getUserId());
        return Optional.ofNullable(runs.get(key(t, u, runId)));
    }

    @Override
    public List<OrchestrationRun> listGated(AgentScope scope, String sessionId) {
        String t = nz(scope == null ? "" : scope.getTenantId());
        String u = nz(scope == null ? "" : scope.getUserId());
        List<OrchestrationRun> result = new ArrayList<>();
        for (OrchestrationRun run : runs.values()) {
            if (isSameScope(run, t, u)
                    && eq(sessionId, run.getSessionId())
                    && OrchestrationRun.PHASE_GATE.equals(run.getPhase())
                    && run.getGateDecision() == null) {
                result.add(run);
            }
        }
        return result;
    }

    @Override
    public Optional<OrchestrationRun> findGated(AgentScope scope, String sessionId, String layerKey) {
        String t = nz(scope == null ? "" : scope.getTenantId());
        String u = nz(scope == null ? "" : scope.getUserId());
        for (OrchestrationRun run : runs.values()) {
            if (isSameScope(run, t, u)
                    && eq(sessionId, run.getSessionId())
                    && eq(layerKey, run.getGateLayer())
                    && OrchestrationRun.PHASE_GATE.equals(run.getPhase())
                    && run.getGateDecision() == null) {
                return Optional.of(run);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean claimForResume(AgentScope scope, String runId) {
        Optional<OrchestrationRun> opt = get(scope, runId);
        if (!opt.isPresent()) {
            return false;
        }
        OrchestrationRun run = opt.get();
        synchronized (run) {
            if (!(OrchestrationRun.PHASE_GATE.equals(run.getPhase())
                    || OrchestrationRun.PHASE_SUSPENDED.equals(run.getPhase())
                    || OrchestrationRun.PHASE_RUNNING.equals(run.getPhase()))) {
                return false;
            }
            run.setPhase(OrchestrationRun.PHASE_RUNNING);
            run.setVersion(run.getVersion() + 1);
            run.setUpdateTime(System.currentTimeMillis());
            return true;
        }
    }

    @Override
    public void delete(AgentScope scope, String runId) {
        runs.remove(key(nz(scope == null ? "" : scope.getTenantId()),
                nz(scope == null ? "" : scope.getUserId()), runId));
    }

    @Override
    public int deleteStale(long cutoff, int max) {
        int removed = 0;
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, OrchestrationRun> e : runs.entrySet()) {
            OrchestrationRun run = e.getValue();
            if (removed >= max) {
                break;
            }
            if (isPendingPhase(run.getPhase()) && run.getUpdateTime() < cutoff) {
                stale.add(e.getKey());
                removed++;
            }
        }
        for (String k : stale) {
            runs.remove(k);
        }
        return removed;
    }

    private static boolean isPendingPhase(String phase) {
        return OrchestrationRun.PHASE_GATE.equals(phase)
                || OrchestrationRun.PHASE_SUSPENDED.equals(phase)
                || OrchestrationRun.PHASE_RUNNING.equals(phase);
    }

    private static String key(String tenant, String user, String runId) {
        return tenant + "::" + user + "::" + runId;
    }

    private static boolean isSameScope(OrchestrationRun run, String tenant, String user) {
        return nz(run.getTenantId()).equals(tenant) && nz(run.getUserId()).equals(user);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}