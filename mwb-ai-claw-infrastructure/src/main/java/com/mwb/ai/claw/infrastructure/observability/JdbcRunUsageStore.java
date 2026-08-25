package com.mwb.ai.claw.infrastructure.observability;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.observability.RunUsage;
import com.mwb.ai.claw.domain.observability.RunUsageStore;

/**
 * JDBC 版运行用量存储（{@code agent.observability.run-usage-store=db}）：落库到 {@code claw_run_usage} 表。
 * <p>
 * 表结构（MySQL 见 start/src/main/resources/schema.sql 或 example-web/db/mysql/framework-schema.sql），
 * 依赖 SQL 脚本建好（与记忆/会话/RAG 同库），表存在性与实现无耦合。
 */
public class JdbcRunUsageStore implements RunUsageStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcRunUsageStore.class);

    private final JdbcTemplate jdbc;

    public JdbcRunUsageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(RunUsage usage) {
        if (usage == null) {
            return;
        }
        try {
            jdbc.update("INSERT INTO claw_run_usage "
                            + "(trace_id, session_id, agent_id, orchestration, model, duration_ms, success, steps, "
                            + "error_code, create_time) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    usage.getTraceId(), usage.getSessionId(), usage.getAgentId(), usage.getOrchestration(),
                    usage.getModel(), usage.getDurationMs(), usage.isSuccess(), usage.getSteps(),
                    usage.getErrorCode(), usage.getCreateTime());
        } catch (Exception e) {
            log.warn("记录运行用量失败: {}", e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> findByDate(String date) {
        try {
            long[] window = dayWindow(date);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT trace_id, session_id, agent_id, orchestration, model, duration_ms, success, steps, "
                            + "error_code, create_time FROM claw_run_usage "
                            + "WHERE create_time >= ? AND create_time < ? ORDER BY create_time ASC",
                    window[0], window[1]);
            List<Map<String, Object>> runs = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                runs.add(toEntry(row));
            }
            return runs;
        } catch (Exception e) {
            log.warn("读取运行用量失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 将查询行转为与 JSONL 定义一致的展示字段 Map（含 ts） */
    private Map<String, Object> toEntry(Map<String, Object> row) {
        Long createTime = row.get("create_time") == null ? null : ((Number) row.get("create_time")).longValue();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", createTime == null ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(createTime), ZoneId.systemDefault())
                        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        entry.put("traceId", row.get("trace_id"));
        entry.put("sessionId", row.get("session_id"));
        entry.put("agentId", row.get("agent_id"));
        entry.put("orchestration", row.get("orchestration"));
        entry.put("model", row.get("model"));
        entry.put("durationMs", row.get("duration_ms"));
        entry.put("success", Boolean.TRUE.equals(row.get("success")));
        entry.put("steps", row.get("steps"));
        entry.put("errorCode", row.get("error_code"));
        return entry;
    }

    /** 计算某天（yyyy-MM-dd，空=今天）的 [start, end) epoch 毫秒窗口 */
    private long[] dayWindow(String date) {
        LocalDateTime start;
        if (date == null || date.trim().isEmpty()) {
            start = LocalDateTime.now().toLocalDate().atStartOfDay();
        } else {
            try {
                start = LocalDate.parse(date.trim()).atStartOfDay();
            } catch (Exception ignore) {
                // 非法日期回退今天
                start = LocalDateTime.now().toLocalDate().atStartOfDay();
            }
        }
        ZoneId zone = ZoneId.systemDefault();
        long from = start.atZone(zone).toInstant().toEpochMilli();
        long to = start.plusDays(1).atZone(zone).toInstant().toEpochMilli();
        return new long[] {from, to};
    }
}