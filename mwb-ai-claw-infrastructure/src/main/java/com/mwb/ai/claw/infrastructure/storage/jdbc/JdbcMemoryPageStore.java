package com.mwb.ai.claw.infrastructure.storage.jdbc;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.domain.memory.MemoryPageStore;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * JDBC 版记忆页存储（agent.storage.type=jdbc）：claw_fact / claw_memory_page 表。
 * <p>
 * - 事实表按 (tenant, user, fact_key) 主键去重（同 key 合并落在 DB 层）；
 * - 记忆页表按 (tenant, user, page_id) 主键，page_type 区分 SUMMARY / ARCHIVE；
 * - tenant_id / user_id 用空字符串 '' 表示默认空间（MySQL 主键列不允许 NULL）。
 */
public class JdbcMemoryPageStore implements MemoryPageStore {

    private final JdbcTemplate jdbc;

    public JdbcMemoryPageStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
    }

    @Override
    public List<MemoryPage> loadFacts(AgentScope scope) {
        ScopeClause where = scopeWhere(scope);
        String sql = "SELECT fact_key AS key, content, importance, session_id, version, token_count, create_time "
                + "FROM claw_fact WHERE " + where.sql + " ORDER BY importance DESC";
        return jdbc.query(sql, (rs, i) -> {
            MemoryPage page = new MemoryPage();
            page.setPageId("fact-" + rs.getString("key"));
            page.setType(MemoryPage.PageType.FACT);
            page.setKey(rs.getString("key"));
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
    }

    @Override
    public void deleteSessionPages(AgentScope scope, String sessionId) {
        ScopeClause where = scopeWhere(scope);
        String sql = "DELETE FROM claw_memory_page WHERE page_type='SUMMARY' AND session_id = ? AND " + where.sql;
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(where.args);
        jdbc.update(sql, args.toArray());
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
        ScopeClause where = scopeWhere(scope);
        String countSql = "SELECT COUNT(*) FROM claw_memory_page WHERE page_id = ? AND " + where.sql;
        List<Object> countArgs = new ArrayList<>();
        countArgs.add(page.getPageId());
        countArgs.addAll(where.args);
        Integer cnt = jdbc.queryForObject(countSql, Integer.class, countArgs.toArray());
        if (cnt != null && cnt > 0) {
            String sql = "UPDATE claw_memory_page SET page_type=?, session_id=?, block_start=?, block_end=?, "
                    + "content=?, token_count=?, create_time=? WHERE page_id = ? AND " + where.sql;
            List<Object> args = new ArrayList<>();
            args.add(page.getType() == null ? null : page.getType().name());
            args.add(page.getSessionId());
            args.add(page.getBlockStart());
            args.add(page.getBlockEnd());
            args.add(page.getContent());
            args.add(page.getTokenCount());
            args.add(page.getCreateTime());
            args.add(page.getPageId());
            args.addAll(where.args);
            jdbc.update(sql, args.toArray());
        } else {
            String sql = "INSERT INTO claw_memory_page (tenant_id, user_id, page_id, page_type, session_id, "
                    + "block_start, block_end, content, token_count, create_time) VALUES (?,?,?,?,?,?,?,?,?,?)";
            jdbc.update(sql, tid(scope), uid(scope), page.getPageId(),
                    page.getType() == null ? null : page.getType().name(),
                    page.getSessionId(), page.getBlockStart(), page.getBlockEnd(),
                    page.getContent(), page.getTokenCount(), page.getCreateTime());
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
