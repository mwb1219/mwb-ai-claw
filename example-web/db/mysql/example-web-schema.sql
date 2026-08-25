-- ============================================================
-- example-web 项目自身数据库脚本（MySQL 版）
-- 仅含接入方用户表 claw_user（对应 UserStorage / 登录鉴权）。
-- 框架内置表（claw_session / claw_fact / claw_memory_page /
-- claw_long_term / claw_trace / claw_run_usage）见 framework-schema.sql。
--
-- 执行方式：mysql -u<user> -p <database> < example-web-schema.sql
-- ============================================================

-- 与 example-web 的 UserStorage 对应：username 唯一（同时作为框架 AgentScope.userId），
-- api_key 为登录凭证（鉴权时按此列反查用户名）。
CREATE TABLE IF NOT EXISTS claw_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（聚簇索引，非业务字段）',
    tenant_id     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '租户 id（example-web 固定单租户）',
    username      VARCHAR(64)  NOT NULL COMMENT '用户名（唯一，同时作为 userId）',
    name          VARCHAR(128) DEFAULT NULL COMMENT '显示名',
    api_key       VARCHAR(128) NOT NULL COMMENT 'API Key（登录凭证，注册/登录签发）',
    tools         LONGTEXT     DEFAULT NULL COMMENT '可用工具名列表 JSON',
    password_hash VARCHAR(255) DEFAULT NULL COMMENT '密码哈希（salt:hash）',
    created_at    BIGINT       DEFAULT NULL COMMENT '创建时间戳（epoch 毫秒）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user (tenant_id, username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表：example-web 单租户用户体系';

CREATE INDEX idx_user_api_key ON claw_user(api_key);
