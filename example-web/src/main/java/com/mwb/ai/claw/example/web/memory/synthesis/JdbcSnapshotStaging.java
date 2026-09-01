package com.mwb.ai.claw.example.web.memory.synthesis;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesisDispatcher.Kind;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.util.JsonUtils;
/**
 * JDBC 快照暂存实现：使用 {@code claw_memory_snapshot} 表。
 * <p>
 * - 使用 {@code (tenant_id, user_id, session_id, task_kind, version)} 唯一键做幂等写入；
 * - 快照序列化为 JSON 存入 {@code snapshot_data}（TEXT 列，最大 64KB；若超过需改用 MEDIUMTEXT / LONGBLOB 或压缩）；
 * - 消费完成后显式 delete；配合 {@code idx_create_time} 索引可做过期清理。
 */
public class JdbcSnapshotStaging implements SnapshotStaging {

    private static final Logger log = LoggerFactory.getLogger(JdbcSnapshotStaging.class);

    private static final long DEFAULT_TTL_MS = 24L * 60 * 60 * 1000; // 24h

    private final JdbcTemplate jdbc;

    public JdbcSnapshotStaging(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long save(AgentScope scope, String sessionId, Kind kind, List<Message> snapshot) {
        long version = System.currentTimeMillis();
        String json = JsonUtils.toJson(snapshot);
        long now = System.currentTimeMillis();
        try {
            // INSERT IGNORE：命中唯一键冲突时忽略（天然去重）
            jdbc.update(
                    "INSERT IGNORE INTO claw_memory_snapshot " +
                    "(tenant_id, user_id, session_id, task_kind, snapshot_data, version, create_time) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    safe(scope.getTenantId()), safe(scope.getUserId()),
                    sessionId, kind.name(), json, version, now);
        } catch (Exception e) {
            // 忽略写失败（staging 写失败不应阻塞主链路，MQ 发送层会处理）
            log.warn("快照暂存失败（忽略）：sessionId={}, kind={}: {}", sessionId, kind, e.getMessage());
        }
        return version;
    }

    @Override
    public List<Message> load(AgentScope scope, String sessionId, Kind kind, long version) {
        String json = jdbc.queryForObject(
                "SELECT snapshot_data FROM claw_memory_snapshot " +
                "WHERE tenant_id = ? AND user_id = ? AND session_id = ? AND task_kind = ? AND version = ?",
                String.class,
                safe(scope.getTenantId()), safe(scope.getUserId()),
                sessionId, kind.name(), version);
        if (json == null) {
            log.warn("staging 快照不存在：sessionId={}, kind={}, version={}", sessionId, kind, version);
            return null;
        }
        try {
            return JsonUtils.fromJson(json, new com.fasterxml.jackson.core.type.TypeReference<List<Message>>() {});
        } catch (Exception e) {
            log.error("staging 快照反序列化失败：sessionId={}, kind={}, version={}", sessionId, kind, version, e);
            return null;
        }
    }

    @Override
    public void delete(AgentScope scope, String sessionId, Kind kind, long version) {
        jdbc.update(
                "DELETE FROM claw_memory_snapshot " +
                "WHERE tenant_id = ? AND user_id = ? AND session_id = ? AND task_kind = ? AND version = ?",
                safe(scope.getTenantId()), safe(scope.getUserId()),
                sessionId, kind.name(), version);
    }

    @Override
    public int cleanupExpired(long ttlMillis) {
        long cutoff = System.currentTimeMillis() - (ttlMillis > 0 ? ttlMillis : DEFAULT_TTL_MS);
        int deleted = jdbc.update(
                "DELETE FROM claw_memory_snapshot WHERE create_time < ?", cutoff);
        if (deleted > 0) {
            log.info("staging 过期快照清理：删除 {} 条（cutoff={}）", deleted, cutoff);
        }
        return deleted;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 定时清理触发器（Spring scheduling 时调用）。默认 24h TTL。
     */
    public int cleanupDefault() {
        return cleanupExpired(DEFAULT_TTL_MS);
    }
}
