package com.mwb.ai.claw.infrastructure.memory.storage.jdbc;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.memory.gateway.MemoryGateway;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * JDBC 版会话存储（agent.storage.type=jdbc）：claw_session 表，messages 以 JSON CLOB 存储（与现有 Session 序列化一致）。
 * <p>
 * tenant_id / user_id 用空字符串 '' 表示默认空间（MySQL 主键列不允许 NULL，与文件模式 legacy 语义对齐）。
 */
public class JdbcSessionGateway implements MemoryGateway {

    private final JdbcTemplate jdbc;

    public JdbcSessionGateway(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveSession(Session session) {
        ScopeClause where = scopeWhere(session.getTenantId(), session.getUserId());
        String countSql = "SELECT COUNT(*) FROM claw_session WHERE session_id = ? AND " + where.sql;
        List<Object> countArgs = new ArrayList<>();
        countArgs.add(session.getSessionId());
        countArgs.addAll(where.args);
        Integer cnt = jdbc.queryForObject(countSql, Integer.class, countArgs.toArray());
        String status = session.getStatus() == null ? null : session.getStatus().name();
        if (cnt != null && cnt > 0) {
            String sql = "UPDATE claw_session SET agent_id=?, title=?, status=?, version=?, update_time=?, "
                    + "messages=? WHERE session_id = ? AND " + where.sql;
            List<Object> args = new ArrayList<>();
            args.add(session.getAgentId());
            args.add(session.getTitle());
            args.add(status);
            args.add(session.getVersion());
            args.add(session.getUpdateTime());
            args.add(JsonUtils.toJson(session));
            args.add(session.getSessionId());
            args.addAll(where.args);
            jdbc.update(sql, args.toArray());
        } else {
            String sql = "INSERT INTO claw_session (tenant_id, user_id, session_id, agent_id, title, status, "
                    + "version, create_time, update_time, messages) VALUES (?,?,?,?,?,?,?,?,?,?)";
            jdbc.update(sql, norm(session.getTenantId()), norm(session.getUserId()), session.getSessionId(),
                    session.getAgentId(), session.getTitle(), status,
                    session.getVersion(), session.getCreateTime(), session.getUpdateTime(),
                    JsonUtils.toJson(session));
        }
    }

    @Override
    public Session getSession(AgentScope scope, String sessionId) {
        ScopeClause where = scopeWhere(scope);
        String sql = "SELECT messages FROM claw_session WHERE session_id = ? AND " + where.sql;
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(where.args);
        List<String> rows = jdbc.queryForList(sql, String.class, args.toArray());
        if (rows.isEmpty()) {
            return null;
        }
        try {
            return JsonUtils.fromJson(rows.get(0), Session.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<Session> listSessions(AgentScope scope) {
        ScopeClause where = scopeWhere(scope);
        String sql = "SELECT messages FROM claw_session WHERE " + where.sql + " ORDER BY update_time DESC";
        List<String> rows = jdbc.queryForList(sql, String.class, where.args.toArray());
        List<Session> sessions = new ArrayList<>();
        for (String row : rows) {
            try {
                sessions.add(JsonUtils.fromJson(row, Session.class));
            } catch (Exception e) {
                // 跳过损坏行
            }
        }
        return sessions;
    }

    @Override
    public void deleteSession(AgentScope scope, String sessionId) {
        ScopeClause where = scopeWhere(scope);
        String sql = "DELETE FROM claw_session WHERE session_id = ? AND " + where.sql;
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(where.args);
        jdbc.update(sql, args.toArray());
    }

    /** 构造 scope 匹配子句（tenant_id/user_id 为空时匹配空字符串默认空间）与参数 */
    private ScopeClause scopeWhere(AgentScope scope) {
        return scopeWhere(scope == null ? null : scope.getTenantId(),
                scope == null ? null : scope.getUserId());
    }

    private ScopeClause scopeWhere(String tenantId, String userId) {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>();
        appendMatch(sql, args, "tenant_id", tenantId);
        sql.append(" AND ");
        appendMatch(sql, args, "user_id", userId);
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

    /** NULL/空串统一归一为空字符串（默认空间），与 MySQL 主键列非空约束一致 */
    private String norm(String value) {
        return value == null ? "" : value;
    }

    /** scope 匹配子句（SQL 片段 + 参数列表） */
    private static final class ScopeClause {
        final String sql;
        final List<Object> args;

        ScopeClause(String sql, List<Object> args) {
            this.sql = sql;
            this.args = args;
        }
    }
}
