package com.mwb.ai.claw.infrastructure.memory.storage.jdbc;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.memory.model.MemoryPage;
import com.mwb.ai.claw.domain.memory.store.MemoryPageStore;
import com.mwb.ai.claw.domain.memory.store.MemorySearchable;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.memory.storage.redis.RedisMemoryIndexer;

/**
 * JDBC 版记忆页存储（agent.storage.type=db）：claw_fact / claw_memory_page 表。
 * <p>
 * - 事实表按 (tenant, user, fact_key) 主键去重（同 key 合并落在 DB 层）；
 * - 记忆页表按 (tenant, user, page_id) 主键，page_type 区分 SUMMARY / ARCHIVE；
 * - tenant_id / user_id 用空字符串 '' 表示默认空间（MySQL 主键列不允许 NULL）；
 * - MySQL 为权威存储（纯文本，无向量列）；召回统一委托 Redis 检索索引
 *   （{@link MemorySearchable}，db 形态下为 {@code RedisMemorySearchable}），
 *   写 MySQL 成功后由 {@link RedisMemoryIndexer} 同步双写到 Redis；
 * - 未注入 Redis 检索能力时（无 spring-data-redis 依赖）：双写跳过、召回委托返回空，
 *   召回策略层回退为全量加载 + 应用层打分。
 */
public class JdbcMemoryPageStore implements MemoryPageStore, MemorySearchable {

    private static final Logger log = LoggerFactory.getLogger(JdbcMemoryPageStore.class);

    private final JdbcTemplate jdbc;
    private final RedisMemoryIndexer indexer;
    private final MemorySearchable searchable;

    /** 兼容构造：无 Redis 双写 / 检索下推（存储职责完整，召回走应用层回退）。 */
    public JdbcMemoryPageStore(JdbcTemplate jdbc) {
        this(jdbc, null, null);
    }

    public JdbcMemoryPageStore(JdbcTemplate jdbc,
                               RedisMemoryIndexer indexer,
                               MemorySearchable searchable) {
        this.jdbc = jdbc;
        this.indexer = indexer;
        this.searchable = searchable;
    }

    // ==================== MemorySearchable：委托 Redis 检索索引（db 形态召回） ====================

    @Override
    public List<MemoryPage> searchFacts(AgentScope scope, List<String> terms, int topK) {
        return searchable != null ? searchable.searchFacts(scope, terms, topK) : new ArrayList<>();
    }

    @Override
    public List<MemoryPage> searchPages(AgentScope scope, List<String> terms, int topK) {
        return searchable != null ? searchable.searchPages(scope, terms, topK) : new ArrayList<>();
    }

    @Override
    public List<MemoryPage> searchByVector(AgentScope scope, float[] queryVector, int topK) {
        return searchable != null ? searchable.searchByVector(scope, queryVector, topK) : new ArrayList<>();
    }

    // ==================== 摘要页 ====================

    @Override
    public void saveSummary(AgentScope scope, MemoryPage page) {
        page.setType(MemoryPage.PageType.SUMMARY);
        upsertPage(scope, page);
    }

    @Override
    public List<MemoryPage> loadSummaries(AgentScope scope, String sessionId) {
        ScopeClause where = scopeWhere(scope);
        String sql = "SELECT * FROM claw_memory_page WHERE page_type='SUMMARY' AND session_id = ? AND " + where.sql
                + " ORDER BY block_start ASC";
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(where.args);
        return jdbc.query(sql, (rs, i) -> toPage(rs), args.toArray());
    }

    @Override
    public List<MemoryPage> listAllSummaries(AgentScope scope) {
        return queryPages(scope, "SUMMARY");
    }

    // ==================== 事实 ====================

    @Override
    public void appendFact(AgentScope scope, MemoryPage fact) {
        // 同 key 合并去重落在 DB 层：存在则更新（版本自增，时间戳保留最新），否则插入
        ScopeClause where = scopeWhere(scope);
        String countSql = "SELECT COUNT(*) FROM claw_fact WHERE fact_key = ? AND " + where.sql;
        List<Object> countArgs = new ArrayList<>();
        countArgs.add(fact.getKey());
        countArgs.addAll(where.args);
        Integer cnt = jdbc.queryForObject(countSql, Integer.class, countArgs.toArray());
        if (cnt != null && cnt > 0) {
            String sql = "UPDATE claw_fact SET content=?, importance=?, session_id=?, version=version+1, "
                    + "token_count=?, update_time=? WHERE fact_key = ? AND " + where.sql;
            List<Object> args = new ArrayList<>();
            args.add(fact.getContent());
            args.add(fact.getImportance());
            args.add(fact.getSessionId());
            args.add(fact.getTokenCount());
            args.add(System.currentTimeMillis());
            args.add(fact.getKey());
            args.addAll(where.args);
            jdbc.update(sql, args.toArray());
        } else {
            String sql = "INSERT INTO claw_fact (tenant_id, user_id, fact_key, content, importance, "
                    + "session_id, version, token_count, create_time, update_time) VALUES (?,?,?,?,?,?,?,?,?,?)";
            jdbc.update(sql, tid(scope), uid(scope), fact.getKey(), fact.getContent(),
                    fact.getImportance(), fact.getSessionId(), fact.getVersion(),
                    fact.getTokenCount(), fact.getCreateTime(), System.currentTimeMillis());
        }
        // MySQL 权威写入成功后同步双写 Redis 派生索引（失败不阻断主事务）
        if (indexer != null) {
            indexer.upsertFact(scope, fact);
        }
    }

    @Override
    public List<MemoryPage> loadFacts(AgentScope scope) {
        ScopeClause where = scopeWhere(scope);
        String sql = "SELECT fact_key AS factKey, content, importance, session_id, version, token_count, create_time "
                + "FROM claw_fact WHERE " + where.sql + " ORDER BY importance DESC";
        return jdbc.query(sql, (rs, i) -> {
            MemoryPage page = new MemoryPage();
            page.setPageId("fact-" + rs.getString("factKey"));
            page.setType(MemoryPage.PageType.FACT);
            page.setKey(rs.getString("factKey"));
            page.setContent(rs.getString("content"));
            page.setImportance(rs.getDouble("importance"));
            page.setSessionId(rs.getString("session_id"));
            page.setVersion(rs.getInt("version"));
            page.setTokenCount(rs.getInt("token_count"));
            page.setCreateTime(rs.getLong("create_time"));
            return page;
        }, where.args.toArray());
    }

    @Override
    public void deleteFact(AgentScope scope, String key) {
        ScopeClause where = scopeWhere(scope);
        String sql = "DELETE FROM claw_fact WHERE fact_key = ? AND " + where.sql;
        List<Object> args = new ArrayList<>();
        args.add(key);
        args.addAll(where.args);
        jdbc.update(sql, args.toArray());
        if (indexer != null) {
            indexer.deleteFact(scope, key);
        }
    }

    @Override
    public void upsertFactAtomic(AgentScope scope, MemoryPage fact) {
        // Phase 1：原生 UPSERT，消除"读 existing → delete → append"的 RMW 竞态。
        // 利用 uk_fact(tenant,user,fact_key) 唯一键，冲突时 importance 取 GREATEST 不回退。
        String sql = "INSERT INTO claw_fact (tenant_id, user_id, fact_key, content, importance, "
                + "session_id, version, token_count, create_time, update_time) "
                + "VALUES (?,?,?,?,?,?,1,?,?,?) "
                + "ON DUPLICATE KEY UPDATE content=VALUES(content), "
                + "importance=GREATEST(importance, VALUES(importance)), "
                + "session_id=VALUES(session_id), version=version+1, "
                + "token_count=VALUES(token_count), update_time=VALUES(update_time)";
        try {
            long now = System.currentTimeMillis();
            jdbc.update(sql, tid(scope), uid(scope), fact.getKey(), fact.getContent(),
                    fact.getImportance(), fact.getSessionId(),
                    fact.getTokenCount(), fact.getCreateTime() > 0 ? fact.getCreateTime() : now, now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 极端并发兜底
            log.warn("事实 UPSERT 命中唯一键冲突（已被其他实例写入）: key={}", fact.getKey());
        }
        if (indexer != null) {
            indexer.upsertFact(scope, fact);
        }
    }

    @Override
    public void deleteSessionPages(AgentScope scope, String sessionId) {
        ScopeClause where = scopeWhere(scope);
        String sql = "DELETE FROM claw_memory_page WHERE page_type='SUMMARY' AND session_id = ? AND " + where.sql;
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(where.args);
        jdbc.update(sql, args.toArray());
        if (indexer != null) {
            indexer.deleteSessionPages(scope, sessionId);
        }
    }

    // ==================== 归档（跨会话 RAG） ====================

    @Override
    public void saveArchive(AgentScope scope, MemoryPage page) {
        page.setType(MemoryPage.PageType.ARCHIVE);
        upsertPage(scope, page);
    }

    @Override
    public List<MemoryPage> loadArchive(AgentScope scope, String sessionId) {
        ScopeClause where = scopeWhere(scope);
        String sql = "SELECT * FROM claw_memory_page WHERE page_type='ARCHIVE' AND session_id = ? AND " + where.sql
                + " ORDER BY block_start ASC";
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(where.args);
        return jdbc.query(sql, (rs, i) -> toPage(rs), args.toArray());
    }

    @Override
    public List<MemoryPage> listAllArchive(AgentScope scope) {
        return queryPages(scope, "ARCHIVE");
    }

    @Override
    public void deleteSessionArchive(AgentScope scope, String sessionId) {
        ScopeClause where = scopeWhere(scope);
        String sql = "DELETE FROM claw_memory_page WHERE page_type='ARCHIVE' AND session_id = ? AND " + where.sql;
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(where.args);
        jdbc.update(sql, args.toArray());
        if (indexer != null) {
            indexer.deleteSessionArchive(scope, sessionId);
        }
    }

    // ==================== Phase 2：无锁 CAS 边界游标 claim ====================

    @Override
    public int claimSummaryBlock(AgentScope scope, String sessionId,
                                 int desiredStart, int blockSize, int snapshotSize) {
        return claimBlockInternal(scope, sessionId, "summary", desiredStart, blockSize, snapshotSize);
    }

    @Override
    public int claimArchiveBlock(AgentScope scope, String sessionId,
                                 int desiredStart, int blockSize, int snapshotSize) {
        return claimBlockInternal(scope, sessionId, "archive", desiredStart, blockSize, snapshotSize);
    }

    /**
     * CAS 抢占"下一段写区间"的内部实现：
     * 1. 确保 boundary 行存在（不存在则 INSERT 默认值）
     * 2. 读取当前游标值 + version
     * 3. 计算目标游标 = current + blockSize
     * 4. 判断是否有可写块：current >= snapshotSize 则返回 -1
     * 5. CAS UPDATE：WHERE version=? AND cursor=? AND cursor+blockSize <= snapshotSize
     *    受影响行数=1 → 抢占成功，返回旧 cursor 值
     *    受影响行数=0 → 被并发抢占，返回 -1（由调用方重试）
     */
    private int claimBlockInternal(AgentScope scope, String sessionId, String cursorType,
                                   int desiredStart, int blockSize, int snapshotSize) {
        String cursorCol = "summary".equals(cursorType) ? "summary_end" : "archive_end";

        // 1. 确保 boundary 行存在
        ensureBoundaryRow(scope, sessionId);

        // 2. 读取当前游标 + version
        String selectSql = "SELECT " + cursorCol + ", version FROM claw_memory_boundary "
                + "WHERE tenant_id=? AND user_id=? AND session_id=?";
        try {
            BoundaryRow row = jdbc.queryForObject(selectSql, (rs, i) ->
                    new BoundaryRow(rs.getInt(cursorCol), rs.getInt("version")),
                    tid(scope), uid(scope), sessionId);

            if (row == null) {
                log.warn("CAS claim: boundary 行不存在（刚 ensure 过？）: sessionId={}", sessionId);
                return -1;
            }

            int currentEnd = row.cursorValue;
            int version = row.version;

            // 3. 无可写块：当前游标 >= 快照末尾
            if (currentEnd >= snapshotSize) {
                return -1;
            }

            // 4. 快照旧于期望位置（desiredStart 是调用方的估计值，可能落后于实际）：
            //    直接用 currentEnd 作为抢占起点，避免永远抢不到
            int claimStart = currentEnd;
            int targetEnd = Math.min(currentEnd + blockSize, snapshotSize);
            int actualBlockSize = targetEnd - currentEnd;

            if (actualBlockSize <= 0) {
                return -1;
            }

            // 5. CAS UPDATE：乐观锁
            String updateSql = "UPDATE claw_memory_boundary SET " + cursorCol + " = ?, "
                    + "version = version + 1, update_time = ? "
                    + "WHERE tenant_id=? AND user_id=? AND session_id=? "
                    + "AND version = ? AND " + cursorCol + " = ?";
            int updated = jdbc.update(updateSql, targetEnd, System.currentTimeMillis(),
                    tid(scope), uid(scope), sessionId, version, currentEnd);

            if (updated == 1) {
                // 抢占成功，返回 claimStart
                return claimStart;
            } else {
                // 被并发抢占
                return -1;
            }
        } catch (Exception e) {
            log.warn("CAS claim 异常: sessionId={}, type={}, error={}", sessionId, cursorType, e.getMessage());
            return -1;
        }
    }

    /**
     * 确保 claw_memory_boundary 表中有该会话的行。
     * 使用 INSERT ... ON DUPLICATE KEY UPDATE 实现幂等初始化。
     */
    private void ensureBoundaryRow(AgentScope scope, String sessionId) {
        String upsertSql = "INSERT INTO claw_memory_boundary "
                + "(tenant_id, user_id, session_id, summary_end, archive_end, version, update_time) "
                + "VALUES (?, ?, ?, 0, 0, 1, ?) "
                + "ON DUPLICATE KEY UPDATE session_id = VALUES(session_id)";
        jdbc.update(upsertSql, tid(scope), uid(scope), sessionId, System.currentTimeMillis());
    }

    /** Boundary 行快照：游标值 + version */
    private static final class BoundaryRow {
        final int cursorValue;
        final int version;

        BoundaryRow(int cursorValue, int version) {
            this.cursorValue = cursorValue;
            this.version = version;
        }
    }

    // ==================== 工具方法 ====================

    private List<MemoryPage> queryPages(AgentScope scope, String pageType) {
        ScopeClause where = scopeWhere(scope);
        String sql = "SELECT * FROM claw_memory_page WHERE page_type = ? AND " + where.sql
                + " ORDER BY block_start ASC";
        List<Object> args = new ArrayList<>();
        args.add(pageType);
        args.addAll(where.args);
        return jdbc.query(sql, (rs, i) -> toPage(rs), args.toArray());
    }

    private void upsertPage(AgentScope scope, MemoryPage page) {
        // Phase 1：原生 UPSERT，消除 COUNT→UPDATE/INSERT 的 RMW 竞态窗口。
        // 利用 uk_scope_session_type_start(tenant,user,session,page_type,block_start) 和
        // uk_page(tenant,user,page_id) 双重唯一键，冲突时更新内容但不回退创建时间。
        String sql = "INSERT INTO claw_memory_page (tenant_id, user_id, page_id, page_type, session_id, "
                + "block_start, block_end, content, token_count, create_time) VALUES (?,?,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE page_id=VALUES(page_id), content=VALUES(content), "
                + "token_count=VALUES(token_count), create_time=LEAST(create_time, VALUES(create_time))";
        try {
            jdbc.update(sql, tid(scope), uid(scope), page.getPageId(),
                    page.getType() == null ? null : page.getType().name(),
                    page.getSessionId(), page.getBlockStart(), page.getBlockEnd(),
                    page.getContent(), page.getTokenCount(), page.getCreateTime());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 极端并发：锁失效后 UNIQUE 键兜底，正确性不回退
            log.warn("记忆页 UPSERT 命中唯一键冲突（已被其他实例写入，跳过）: pageId={}, type={}",
                    page.getPageId(), page.getType());
        }
        // MySQL 权威写入成功后同步双写 Redis 派生索引（失败不阻断主事务）
        if (indexer != null) {
            indexer.upsertPage(scope, page);
        }
    }

    private MemoryPage toPage(java.sql.ResultSet rs) throws java.sql.SQLException {
        MemoryPage page = new MemoryPage();
        page.setPageId(rs.getString("page_id"));
        page.setType(parseType(rs.getString("page_type")));
        page.setSessionId(rs.getString("session_id"));
        page.setBlockStart(rs.getInt("block_start"));
        page.setBlockEnd(rs.getInt("block_end"));
        page.setContent(rs.getString("content"));
        page.setTokenCount(rs.getInt("token_count"));
        page.setCreateTime(rs.getLong("create_time"));
        return page;
    }

    private MemoryPage.PageType parseType(String type) {
        if (type == null) {
            return null;
        }
        try {
            return MemoryPage.PageType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String tid(AgentScope scope) {
        return scope == null || scope.getTenantId() == null ? "" : scope.getTenantId();
    }

    private String uid(AgentScope scope) {
        return scope == null || scope.getUserId() == null ? "" : scope.getUserId();
    }

    private ScopeClause scopeWhere(AgentScope scope) {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        appendMatch(sql, args, "tenant_id", tid(scope));
        sql.append(" AND ");
        appendMatch(sql, args, "user_id", uid(scope));
        return new ScopeClause(sql.toString(), args);
    }

    private void appendMatch(StringBuilder sql, List<Object> args, String column, String value) {
        if (value == null || value.isEmpty()) {
            sql.append(column).append(" = ''");
        } else {
            sql.append(column).append(" = ?");
            args.add(value);
        }
    }

    private static final class ScopeClause {
        final String sql;
        final List<Object> args;

        ScopeClause(String sql, List<Object> args) {
            this.sql = sql;
            this.args = args;
        }
    }
}
