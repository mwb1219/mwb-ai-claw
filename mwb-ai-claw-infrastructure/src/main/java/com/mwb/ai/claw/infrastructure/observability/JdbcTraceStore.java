package com.mwb.ai.claw.infrastructure.observability;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.observability.TraceRun;
import com.mwb.ai.claw.domain.observability.TraceStep;
import com.mwb.ai.claw.domain.observability.TraceStore;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * JDBC 版步骤级 trace 存储（{@code agent.observability.trace.store=db}）：落库到 {@code claw_trace} 表。
 * <p>
 * 表结构（MySQL 见启动模块 schema.sql，PostgreSQL 见 example-web initdb 01-pgvector.sql）：
 * 每次运行写入一行 {@code step_type='__run__'} 的 run 标识行 + 每步一行明细，
 * 按 traceId 查询时以 {@code step_index} 还原步骤链路。表依赖 SQL 脚本建好（与记忆四表一致，不在代码建表）。
 */
public class JdbcTraceStore implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcTraceStore.class);

    /** run 标识行的 step_type，不参与步骤还原 */
    private static final String RUN_MARKER = "__run__";

    private static final String INSERT_SQL =
            "INSERT INTO claw_trace (tenant_id, user_id, trace_id, session_id, agent_id, orchestration, model, "
            + "start_time, duration_ms, success, error_code, step_index, step_type, step_content, create_time) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final int[] ARG_TYPES = {
            Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
            Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.BIGINT, Types.BOOLEAN,
            Types.VARCHAR, Types.INTEGER, Types.VARCHAR, Types.VARCHAR, Types.BIGINT
    };

    private final JdbcTemplate jdbc;

    public JdbcTraceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveTrace(TraceRun trace) {
        if (trace == null || trace.getTraceId() == null || trace.getTraceId().trim().isEmpty()) {
            return;
        }
        try {
            List<Object[]> batch = new ArrayList<>();
            long now = System.currentTimeMillis();
            // run 标识行
            batch.add(row(trace, 0, RUN_MARKER, null, now));
            List<TraceStep> steps = trace.getSteps();
            if (steps != null) {
                for (int i = 0; i < steps.size(); i++) {
                    TraceStep s = steps.get(i);
                    batch.add(row(trace, i + 1, s.getType(), s.getContent(), now));
                }
            }
            jdbc.batchUpdate(INSERT_SQL, batch, ARG_TYPES);
        } catch (Exception e) {
            log.warn("保存 trace 失败: {}", e.getMessage());
        }
    }

    @Override
    public TraceRun findTrace(AgentScope scope, String traceId) {
        if (traceId == null || traceId.trim().isEmpty()) {
            return null;
        }
        try {
            String tid = nz(scope == null ? "" : scope.getTenantId());
            String uid = nz(scope == null ? "" : scope.getUserId());
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM claw_trace WHERE trace_id = ? AND tenant_id = ? AND user_id = ? "
                            + "ORDER BY step_index ASC", traceId, tid, uid);
            if (rows.isEmpty()) {
                return null;
            }
            return toRun(rows);
        } catch (Exception e) {
            log.warn("读取 trace 失败: {}", e.getMessage());
            return null;
        }
    }

    private Object[] row(TraceRun trace, int stepIndex, String stepType, String stepContent, long createTime) {
        return new Object[] {
                nz(trace.getTenantId()), nz(trace.getUserId()), trace.getTraceId(),
                trace.getSessionId(), trace.getAgentId(), trace.getOrchestration(), trace.getModel(),
                trace.getStartTime(), trace.getDurationMs(), trace.isSuccess(), trace.getErrorCode(),
                stepIndex, stepType, stepContent, createTime
        };
    }

    private TraceRun toRun(List<Map<String, Object>> rows) {
        TraceRun run = new TraceRun();
        List<TraceStep> steps = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (RUN_MARKER.equals(row.get("step_type"))) {
                // run 标识行 → 填充 run 级字段
                run.setTraceId(str(row.get("trace_id")));
                run.setTenantId(nz(str(row.get("tenant_id"))));
                run.setUserId(nz(str(row.get("user_id"))));
                run.setSessionId(str(row.get("session_id")));
                run.setAgentId(str(row.get("agent_id")));
                run.setOrchestration(str(row.get("orchestration")));
                run.setModel(str(row.get("model")));
                run.setStartTime(lng(row.get("start_time")));
                run.setDurationMs(lng(row.get("duration_ms")));
                run.setSuccess(Boolean.TRUE.equals(row.get("success")));
                run.setErrorCode(str(row.get("error_code")));
            } else {
                TraceStep step = new TraceStep();
                step.setIndex((int) lng(row.get("step_index")));
                step.setType(str(row.get("step_type")));
                step.setContent(str(row.get("step_content")));
                steps.add(step);
            }
        }
        run.setSteps(steps);
        return run;
    }

    private String str(Object v) {
        return v == null ? null : v.toString();
    }

    private long lng(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}