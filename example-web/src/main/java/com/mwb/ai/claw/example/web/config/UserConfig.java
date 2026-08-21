package com.mwb.ai.claw.example.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.example.web.storage.FileUserStorage;
import com.mwb.ai.claw.example.web.storage.MySqlUserStorage;
import com.mwb.ai.claw.example.web.storage.UserStorage;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

/**
 * 用户存储装配：根据 {@code example.user-storage.type} 选择文件或 MySQL 实现，
 * 注册为 {@code UserStorage} Bean（同时作为框架 {@code TenantGateway} 供鉴权反查 API Key）。
 */
@Configuration
public class UserConfig {

    /** example-web 固定单租户 id */
    @Value("${example.tenant-id:default}")
    private String tenantId;

    /** 文件存储（默认）：{memoryDir}/users.json */
    @Bean
    @ConditionalOnProperty(name = "example.user-storage.type", havingValue = "file", matchIfMissing = true)
    public UserStorage fileUserStorage(AgentProperties properties) {
        return new FileUserStorage(properties, tenantId);
    }

    /** MySQL 存储：claw_user 表（见 schema.sql） */
    @Bean
    @ConditionalOnProperty(name = "example.user-storage.type", havingValue = "mysql")
    public UserStorage mysqlUserStorage(JdbcTemplate jdbc) {
        return new MySqlUserStorage(jdbc, tenantId);
    }
}
