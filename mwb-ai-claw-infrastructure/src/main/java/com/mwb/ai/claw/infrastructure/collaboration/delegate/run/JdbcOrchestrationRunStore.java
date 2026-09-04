package com.mwb.ai.claw.infrastructure.collaboration.delegate.run;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.util.JsonUtils;

/**
 * JDBC 版委托编排运行记录存储（{@code agent.collaboration.orchestration-run.store=db}）：
 * 落库到 {@code claw_orchestration_run} 表（MySQL 见 start/src/main/resources/schema.sql），跨请求 + 跨实例持久化。
 * <p>
 * 所有查询以 {@code (tenant_id, user_id)} 过滤（对齐 trace 租户隔离）；plan 存 JSON 文本、
 * trace 序列化为 JSON 文本，避免模型依赖具体 Todo 类型。下辖 CURD 与续跑认领（CAS）。
 */
public class JdbcOrchestrationRunStore implements OrchestrationRunStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcOrchestrationRunStore.class);

    private static final String INSERT_SQL = "INSERT INTO claw_orchestration_run "
            + "(tenant_id, user_id, run_id, session_id, orchestration_id, phase, version, task, planner_agent_id, "
            + "plan_json, trace_json, artifact_dir, reply, agent_id, gate_layer, gate_decision, "
            + "stack_json, create_time, update_time) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String UPDATE_SQL = "UPDATE claw_orchestration_run SET phase=?, version=version+1, task=?, "
            + "planner_agent_id=?, plan_json=?, trace_json=?, artifact_dir=?, reply=?, agent_id=?, gate_layer=?, "
            + "gate_decision=?, stack_json=?, update_time=? WHERE tenant_id=? AND user_id=? AND run_id=?";

    private static final int[] INSERT_TYPES = {
            Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
            Types.VARCHAR, Types.BIGINT, Types.VARCHAR, Types.VARCHAR,
            Types.LONGVARCHAR, Types.LONGVARCHAR, Types.VARCHAR, Types.LONGVARCHAR,
            Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
            Types.LONGVARCHAR, Types.BIGINT, Types.BIGINT
    };

    private final JdbcTemplate jdbc;

    public JdbcOrchestrationRunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(OrchestrationRun run) {
        if (run == null || run.getRunId() == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            if (run.getCreateTime() == 0) {
                run.setCreateTime(now);
            }
            run.setUpdateTime(now);
            jdbc.update(INSERT_SQL, new Object[] {
                    nz(run.getTenantId()), nz(run.getUserId()), run.getRunId(), run.getSessionId(),
                    run.getOrchestrationId(), run.getPhase(), run.getVersion(), run.getTask(),
                    run.getPlannerAgentId(), run.getPlanJson(), toTraceJson(run.getTrace()),
                    run.getArtifactDir(), run.getReply(), run.getAgentId(), run.getGateLayer(),
                    run.getGateDecision(), run.getStackJson(), run.getCreateTime(), run.getUpdateTime()
            }, INSERT_TYPES);
        } catch (Exception e) {
            log.warn("保存编排运行记录失败: {}", e.getMessage());
        }
    }

    @Override
    public void update(OrchestrationRun run) {
        if (run == null || run.getRunId() == null) {
            return;
        }
        try {
            run.setUpdateTime(System.currentTimeMillis());
            jdbc.update(UPDATE_SQL, run.getPhase(), run.getTask(), run.getPlannerAgentId(),
                    run.getPlanJson(), toTraceJson(run.getTrace()), run.getArtifactDir(), run.getReply(),
                    run.getAgentId(), run.getGateLayer(), run.getGateDecision(), run.getStackJson(),
                    run.getUpdateTime(), nz(run.getTenantId()), nz(run.getUserId()), run.getRunId());
        } catch (Exception e) {
            log.warn("更新编排运行记录失败: {}", e.getMessage());
        }
    }

    @Override
    public Optional<OrchestrationRun> get(AgentScope scope, String runId) {
        if (runId == null || runId.isEmpty()) {
            return Optional.empty();
        }
        String t = nz(scope == null ? "" : scope.getTenantId());
        String u = nz(scope == null ? "" : scope.getUserId());
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM claw_orchestration_run WHERE tenant_id=? AND user_id=? AND run_id=?",
                    t, u, runId);
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(toRun(rows.get(0)));
        } catch (Exception e) {
            log.warn("读取编排运行记录失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<OrchestrationRun> listGated(AgentScope scope, String sessionId) {
        String t = nz(scope == null ? "" : scope.getTenantId());
        String u = nz(scope == null ? "" : scope.getUserId());
        List<OrchestrationRun> result = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM claw_orchestration_run WHERE tenant_id=? AND user_id=? AND session_id=? "
                            + "AND phase=? AND gate_decision IS NULL ORDER BY create_time ASC",
                    t, u, sessionId, OrchestrationRun.PHASE_GATE);
            for (Map<String, Object> row : rows) {
                result.add(toRun(row));
            }
        } catch (Exception e) {
            log.warn("读取待审批运行列表失败: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public Optional<OrchestrationRun> findGated(AgentScope scope, String sessionId, String layerKey) {
        String t = nz(scope == null ? "" : scope.getTenantId());
        String u = nz(scope == null ? "" : scope.getUserId());
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM claw_orchestration_run WHERE tenant_id=? AND user_id=? AND session_id=? "
                            + "AND gate_layer=? AND phase=? AND gate_decision IS NULL",
                    t, u, sessionId, layerKey, OrchestrationRun.PHASE_GATE);
            return rows.isEmpty() ? Optional.empty() : Optional.of(toRun(rows.get(0)));
        } catch (Exception e) {
            log.warn("定位待审批运行失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean claimForResume(AgentScope scope, String runId) {
        String t = nz(scope == null ? "" : scope.getTenantId());
        String u = nz(scope == null ? "" : scope.getUserId());
        try {
            int n = jdbc.update(
                    "UPDATE claw_orchestration_run SET phase=?, version=version+1, update_time=? "
                            + "WHERE tenant_id=? AND user_id=? AND run_id=? AND phase IN (?, ?, ?)",
                    OrchestrationRun.PHASE_RUNNING, System.currentTimeMillis(), t, u, runId,
                    OrchestrationRun.PHASE_GATE, OrchestrationRun.PHASE_SUSPENDED, OrchestrationRun.PHASE_RUNNING);
            return n > 0;
        } catch (Exception e) {
            log.warn("续跑认领失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void delete(AgentScope scope, String runId) {
        try {
            jdbc.update("DELETE FROM claw_orchestration_run WHERE tenant_id=? AND user_id=? AND run_id=?",
                    nz(scope == null ? "" : scope.getTenantId()),
                    nz(scope == null ? "" : scope.getUserId()), runId);
        } catch (Exception e) {
            log.warn("删除编排运行记录失败: {}", e.getMessage());
        }
    }

    @Override
    public int deleteStale(long cutoff, int max) {
        try {
            List<String> ids = new ArrayList<>();
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT run_id FROM claw_orchestration_run WHERE phase IN (?, ?, ?) AND update_time < ? "
                            + "ORDER BY update_time ASC LIMIT ?",
                    OrchestrationRun.PHASE_GATE, OrchestrationRun.PHASE_SUSPENDED, OrchestrationRun.PHASE_RUNNING,
                    cutoff, Math.max(1, max));
            for (Map<String, Object> row : rows) {
                String id = str(row.get("run_id"));
                if (id != null) {
                    ids.add(id);
                }
            }
            int removed = 0;
            for (String id : ids) {
                int n = jdbc.update("DELETE FROM claw_orchestration_run WHERE run_id=?", id);
                removed += n;
            }
            return removed;
        } catch (Exception e) {
            log.warn("清理悬挂编排运行记录失败: {}", e.getMessage());
            return 0;
        }
    }

    private OrchestrationRun toRun(Map<String, Object> row) {
        OrchestrationRun run = new OrchestrationRun();
        run.setRunId(str(row.get("run_id")));
        run.setTenantId(nz(str(row.get("tenant_id"))));
        run.setUserId(nz(str(row.get("user_id"))));
        run.setSessionId(str(row.get("session_id")));
        run.setOrchestrationId(str(row.get("orchestration_id")));
        run.setPhase(str(row.get("phase")));
        run.setVersion(lng(row.get("version")));
        run.setTask(str(row.get("task")));
        run.setPlannerAgentId(str(row.get("planner_agent_id")));
        run.setPlanJson(str(row.get("plan_json")));
        run.setTrace(fromTraceJson(str(row.get("trace_json"))));
        run.setArtifactDir(str(row.get("artifact_dir")));
        run.setReply(str(row.get("reply")));
        run.setAgentId(str(row.get("agent_id")));
        run.setGateLayer(str(row.get("gate_layer")));
        run.setGateDecision(str(row.get("gate_decision")));
        run.setStackJson(str(row.get("stack_json")));
        run.setCreateTime(lng(row.get("create_time")));
        run.setUpdateTime(lng(row.get("update_time")));
        return run;
    }

    private static String toTraceJson(List<String> trace) {
        return trace == null ? "[]" : JsonUtils.toJson(trace);
    }

    private static List<String> fromTraceJson(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return JsonUtils.fromJsonList(json, String.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static long lng(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}