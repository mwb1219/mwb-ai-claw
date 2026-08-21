package com.mwb.ai.claw.example.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.example.web.storage.MySqlUserStorage;
import com.mwb.ai.claw.example.web.storage.UserStorage;

/**
 * 用户存储装配：注册 MySQL 用户存储为 {@code UserStorage} Bean
 * （同时作为框架 {@code TenantGateway} 供鉴权反查 API Key）。
 */
@Configuration
public class UserConfig {

    /** example-web 固定单租户 id */
    @Value("${example.tenant-id:default}")
    private String tenantId;

    /** MySQL 存储：claw_user 表（见 schema.sql），数据源由 spring.datasource 提供 */
    @Bean
    public UserStorage userStorage(JdbcTemplate jdbc) {
        return new MySqlUserStorage(jdbc, tenantId);
    }
}
