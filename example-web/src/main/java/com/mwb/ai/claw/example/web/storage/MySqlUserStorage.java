package com.mwb.ai.claw.example.web.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mwb.ai.claw.example.web.model.User;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * 用户 MySQL 存储（storage 层 mysql 实现）：基于 JdbcTemplate 持久化到 {@code claw_user} 表。
 * <p>
 * 建表脚本见 example-web/src/main/resources/schema.sql；数据源由 {@code spring.datasource} 提供
 * （默认 H2 兼容联调，生产切换 MySQL 驱动与连接串）。
 */
public class MySqlUserStorage implements UserStorage {

    private final JdbcTemplate jdbc;
    private final String tenantId;

    public MySqlUserStorage(JdbcTemplate jdbc, String tenantId) {
        this.jdbc = jdbc;
        this.tenantId = tenantId;
    }

    @Override
    public Optional<User> findByUsername(String username) {
        String sql = "SELECT username, name, api_key, tools, password_hash, created_at FROM claw_user "
                + "WHERE tenant_id = ? AND username = ?";
        List<User> rows = jdbc.query(sql, this::mapRow, tenantId, username);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public User save(User user) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM claw_user WHERE tenant_id = ? AND username = ?",
                Integer.class, tenantId, user.getUsername());
        if (cnt != null && cnt > 0) {
            jdbc.update("UPDATE claw_user SET name=?, api_key=?, tools=?, password_hash=?, created_at=? "
                    + "WHERE tenant_id=? AND username=?",
                    user.getName(), user.getApiKey(), JsonUtils.toJson(user.getTools()),
                    user.getPasswordHash(), user.getCreatedAt(), tenantId, user.getUsername());
        } else {
            jdbc.update("INSERT INTO claw_user (tenant_id, username, name, api_key, tools, password_hash, created_at) "
                    + "VALUES (?,?,?,?,?,?,?)",
                    tenantId, user.getUsername(), user.getName(), user.getApiKey(),
                    JsonUtils.toJson(user.getTools()), user.getPasswordHash(), user.getCreatedAt());
        }
        return user;
    }

    @Override
    public String[] resolveApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return null;
        }
        List<String> rows = jdbc.queryForList(
                "SELECT username FROM claw_user WHERE api_key = ?", String.class, apiKey);
        if (rows.isEmpty()) {
            return null;
        }
        return new String[]{tenantId, rows.get(0)};
    }

    private User mapRow(ResultSet rs, int rowNum) throws SQLException {
        User user = new User();
        user.setUsername(rs.getString("username"));
        user.setName(rs.getString("name"));
        user.setApiKey(rs.getString("api_key"));
        String tools = rs.getString("tools");
        user.setTools(tools == null ? new ArrayList<>()
                : JsonUtils.fromJson(tools, new TypeReference<List<String>>() {}));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setCreatedAt(rs.getLong("created_at"));
        return user;
    }
}
