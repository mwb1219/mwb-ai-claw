package com.mwb.ai.claw.infrastructure.storage.jdbc;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * JDBC 版长期记忆网关（agent.storage.type=jdbc）：claw_long_term 表按 (tenant, user, name) 存取
 * AGENT.md / MEMORY.md 内容（name 列区分）。tenant_id / user_id 用空字符串 '' 表示默认空间
 * （MySQL 主键列不允许 NULL）。
 */
@Component
@ConditionalOnProperty(name = "agent.storage.type", havingValue = "jdbc")
public class JdbcLongTermMemoryGateway implements LongTermMemoryGateway {

    private static final String NAME_AGENT = "AGENT.md";
    private static final String NAME_MEMORY = "MEMORY.md";

    private final JdbcTemplate jdbc;

    public JdbcLongTermMemoryGateway(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String loadAgentInstructions(AgentScope scope) {
        return load(scope, NAME_AGENT);
    }

    @Override
    public String loadMemory(AgentScope scope) {
        return load(scope, NAME_MEMORY);
    }

    @Override
    public void saveMemory(AgentScope scope, String content) {
        upsert(scope, NAME_MEMORY, content);
    }

    private String load(AgentScope scope, String name) {
        ScopeClause where = scopeWhere(scope);
        String sql = "SELECT content FROM claw_long_term WHERE name = ? AND " + where.sql;
        List<Object> args = new ArrayList<>();
        args.add(name);
        args.addAll(where.args);
        List<String> rows = jdbc.queryForList(sql, String.class, args.toArray());
        return rows.isEmpty() || rows.get(0) == null ? "" : rows.get(0);
    }

    private void upsert(AgentScope scope, String name, String content) {
        ScopeClause where = scopeWhere(scope);
        String countSql = "SELECT COUNT(*) FROM claw_long_term WHERE name = ? AND " + where.sql;
        List<Object> countArgs = new ArrayList<>();
        countArgs.add(name);
        countArgs.addAll(where.args);
        Integer cnt = jdbc.queryForObject(countSql, Integer.class, countArgs.toArray());
        if (cnt != null && cnt > 0) {
            String sql = "UPDATE claw_long_term SET content=?, update_time=? WHERE name = ? AND " + where.sql;
            List<Object> args = new ArrayList<>();
            args.add(content);
            args.add(System.currentTimeMillis());
            args.add(name);
            args.addAll(where.args);
            jdbc.update(sql, args.toArray());
        } else {
            jdbc.update("INSERT INTO claw_long_term (tenant_id, user_id, name, content, update_time) VALUES (?,?,?,?,?)",
                    tid(scope), uid(scope), name, content, System.currentTimeMillis());
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
