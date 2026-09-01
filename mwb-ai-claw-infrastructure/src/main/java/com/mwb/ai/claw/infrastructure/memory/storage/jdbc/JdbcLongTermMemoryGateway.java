package com.mwb.ai.claw.infrastructure.memory.storage.jdbc;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * JDBC 版长期记忆网关（agent.storage.type=jdbc）：claw_long_term 表按 (tenant, user, name) 存取
 * AGENT.md / MEMORY.md 内容（name 列区分）。tenant_id / user_id 用空字符串 '' 表示默认空间
 * （MySQL 主键列不允许 NULL）。
 */
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

    @Override
    public void saveAgentInstructions(AgentScope scope, String content) {
        upsert(scope, NAME_AGENT, content);
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

    /**
     * T8/T9 并发安全：单条 INSERT ... ON DUPLICATE KEY UPDATE，消除「SELECT COUNT + INSERT/UPDATE」竞态窗口。
     * 依赖 uk_scope_name(tenant_id,user_id,name) 唯一键：两个实例同时入库同一 (scope,name) 时，
     * MySQL 合并为一次写入（存在则更新），后写者不会报错也不会覆盖丢数据。
     */
    private void upsert(AgentScope scope, String name, String content) {
        jdbc.update(
                "INSERT INTO claw_long_term (tenant_id, user_id, name, content, update_time) VALUES (?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE content=VALUES(content), update_time=VALUES(update_time)",
                tid(scope), uid(scope), name, content, System.currentTimeMillis());
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
