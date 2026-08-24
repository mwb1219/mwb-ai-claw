-- 启用 pgvector 扩展（RAG provider=pgvector 向量库；docker-compose 首次初始化时自动执行）
CREATE EXTENSION IF NOT EXISTS vector;

-- ============ example-web 接入方用户表（UserStorage 需要，PostgreSQL 版） ============
-- 与 schema.sql（MySQL 版）的 claw_user 对齐；初始 01 脚本在 POSTGRES_DB 首启时执行
CREATE TABLE IF NOT EXISTS claw_user (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     VARCHAR(64)  NOT NULL DEFAULT '',
    username      VARCHAR(64)  NOT NULL,
    name          VARCHAR(128) DEFAULT NULL,
    api_key       VARCHAR(128) NOT NULL,
    tools         TEXT         DEFAULT NULL,
    password_hash VARCHAR(255) DEFAULT NULL,
    created_at    BIGINT       DEFAULT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_claw_user ON claw_user(tenant_id, username);
CREATE INDEX IF NOT EXISTS idx_user_api_key ON claw_user(api_key);

-- ============ 记忆存储表（agent.storage.type=db 时使用，PostgreSQL 版） ============
-- 与 schema.sql（MySQL 版）的 claw_session / claw_fact / claw_memory_page / claw_long_term 对齐；
-- tenant_id / user_id 用空字符串 '' 表示默认空间，与 AgentScope / JDBC 存储语义一致。

-- 会话表：会话主体与消息 JSON（消息以 TEXT 存 JSON，与 Session 序列化一致）
CREATE TABLE IF NOT EXISTS claw_session (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT '',
    user_id     VARCHAR(64)  NOT NULL DEFAULT '',
    session_id  VARCHAR(64)  NOT NULL,
    agent_id    VARCHAR(64)  DEFAULT NULL,
    title       VARCHAR(128) DEFAULT NULL,
    status      VARCHAR(16)  DEFAULT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    create_time BIGINT       NOT NULL,
    update_time BIGINT       NOT NULL,
    messages    TEXT         DEFAULT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_session ON claw_session(tenant_id, user_id, session_id);
CREATE INDEX IF NOT EXISTS idx_session_update ON claw_session(tenant_id, user_id, update_time DESC);

-- 事实表：结构化跨会话记忆；同 (tenant,user,fact_key) 合并去重落在 DB 层（version 自增）
CREATE TABLE IF NOT EXISTS claw_fact (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT '',
    user_id     VARCHAR(64)  NOT NULL DEFAULT '',
    fact_key    VARCHAR(128) NOT NULL,
    content     TEXT         NOT NULL,
    importance  DOUBLE PRECISION NOT NULL,
    session_id  VARCHAR(64)  DEFAULT NULL,
    version     INT          NOT NULL DEFAULT 1,
    token_count INT          DEFAULT NULL,
    create_time BIGINT       DEFAULT NULL,
    update_time BIGINT       DEFAULT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_fact ON claw_fact(tenant_id, user_id, fact_key);

-- 记忆页表：摘要页(SUMMARY) 与会话原文归档(ARCHIVE) 统一存储
CREATE TABLE IF NOT EXISTS claw_memory_page (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT '',
    user_id     VARCHAR(64)  NOT NULL DEFAULT '',
    page_id     VARCHAR(128) NOT NULL,
    page_type   VARCHAR(16)  NOT NULL,
    session_id  VARCHAR(64)  DEFAULT NULL,
    block_start INT          DEFAULT NULL,
    block_end   INT          DEFAULT NULL,
    content     TEXT         DEFAULT NULL,
    token_count INT          DEFAULT NULL,
    create_time BIGINT       DEFAULT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_page ON claw_memory_page(tenant_id, user_id, page_id);

-- 长期记忆表：AGENT.md / MEMORY.md 文件级记忆，name 列区分
CREATE TABLE IF NOT EXISTS claw_long_term (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT '',
    user_id     VARCHAR(64)  NOT NULL DEFAULT '',
    name        VARCHAR(32)  NOT NULL,
    content     TEXT         DEFAULT NULL,
    update_time BIGINT       DEFAULT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_long_term ON claw_long_term(tenant_id, user_id, name);