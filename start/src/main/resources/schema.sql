-- ============================================================
-- mwb-ai-claw PhaseA 服务端生产化：MySQL 建表脚本（agent.storage.type=jdbc）
-- 目标数据库：MySQL 5.7+ / 8.0（InnoDB / utf8mb4）
-- 执行方式：mysql -u<user> -p <database> < schema.sql（或导入工具执行）
--
-- 设计约定：
--   - 每表使用自增 id 主键（InnoDB 聚簇索引顺序插入，避免字符串复合主键随机插入/页分裂）；
--   - 租户/用户维度去重由业务唯一键 UNIQUE KEY(tenant_id, user_id, x) 保证，与 JDBC 实现
--     （count → update/insert）完全兼容，Java 代码无需改动；
--   - tenant_id / user_id 用空字符串 '' 表示默认空间（legacy 根目录，与 AgentScope 语义一致）；
--   - 文本字段统一 LONGTEXT（4GB），容纳会话消息 JSON / 记忆正文；
--   - 脚本可重复执行（CREATE TABLE IF NOT EXISTS）；重复执行时 CREATE INDEX / 建唯一键
--     会报 Duplicate key name，属预期行为，可忽略。
-- ============================================================

-- ==================== 会话表 ====================
-- 会话消息以 JSON LONGTEXT 存储（与现有 Session 序列化一致）
CREATE TABLE IF NOT EXISTS claw_session (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（聚簇索引，非业务字段）',
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '租户 id（空串=默认空间）',
    user_id     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '用户 id（空串=默认空间）',
    session_id  VARCHAR(64)  NOT NULL COMMENT '会话 id（全局唯一，配合 scope 定位）',
    agent_id    VARCHAR(64)  DEFAULT NULL COMMENT '归属 Agent id（默认/路由目标）',
    title       VARCHAR(128) DEFAULT NULL COMMENT '会话标题（展示用，可空）',
    status      VARCHAR(16)  DEFAULT NULL COMMENT '会话状态（active/closed 等）',
    version     BIGINT       NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（并发更新冲突检测）',
    create_time BIGINT       NOT NULL COMMENT '创建时间戳（epoch 毫秒）',
    update_time BIGINT       NOT NULL COMMENT '最近更新时间戳（epoch 毫秒，会话列表倒序排序依据）',
    messages    LONGTEXT     DEFAULT NULL COMMENT '消息列表 JSON（与 Session 序列化一致）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session (tenant_id, user_id, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表：会话主体与消息 JSON';

CREATE INDEX idx_session_update ON claw_session(tenant_id, user_id, update_time DESC);

-- ==================== 事实表 ====================
-- 结构化事实，跨会话检索；同 key 合并去重落在 DB 层（version 自增）
CREATE TABLE IF NOT EXISTS claw_fact (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（聚簇索引，非业务字段）',
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '租户 id（空串=默认空间）',
    user_id     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '用户 id（空串=默认空间）',
    fact_key    VARCHAR(128) NOT NULL COMMENT '事实去重键（如 用户偏好-语言）',
    content     LONGTEXT     NOT NULL COMMENT '事实内容文本',
    importance  DOUBLE       NOT NULL COMMENT '重要度 0-1（召回排序依据）',
    session_id  VARCHAR(64)  DEFAULT NULL COMMENT '事实来源会话 id',
    version     INT          NOT NULL DEFAULT 1 COMMENT '同 key 合并时的版本号（更新自增）',
    token_count INT          DEFAULT NULL COMMENT '估算 token 数（预算控制）',
    create_time BIGINT       DEFAULT NULL COMMENT '首次创建时间戳（epoch 毫秒）',
    update_time BIGINT       DEFAULT NULL COMMENT '最近更新时间戳（epoch 毫秒）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_fact (tenant_id, user_id, fact_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事实表：结构化跨会话记忆';

-- ==================== 记忆页表 ====================
-- SUMMARY / ARCHIVE 统一存储：摘要页与会话原文归档
CREATE TABLE IF NOT EXISTS claw_memory_page (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（聚簇索引，非业务字段）',
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '租户 id（空串=默认空间）',
    user_id     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '用户 id（空串=默认空间）',
    page_id     VARCHAR(128) NOT NULL COMMENT '记忆页 id（如摘要块 summary-3）',
    page_type   VARCHAR(16)  NOT NULL COMMENT '页类型：SUMMARY | ARCHIVE',
    session_id  VARCHAR(64)  DEFAULT NULL COMMENT '所属会话 id',
    block_start INT          DEFAULT NULL COMMENT '摘要覆盖的消息区间起点（含）',
    block_end   INT          DEFAULT NULL COMMENT '摘要覆盖的消息区间终点（不含）',
    content     LONGTEXT     DEFAULT NULL COMMENT '页内容（摘要文本 / 归档原文）',
    token_count INT          DEFAULT NULL COMMENT '估算 token 数（预算控制）',
    create_time BIGINT       DEFAULT NULL COMMENT '创建时间戳（epoch 毫秒）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_page (tenant_id, user_id, page_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='记忆页表：摘要页与会话归档';

-- ==================== 长期记忆表 ====================
-- AGENT.md / MEMORY.md 文件级记忆，name 列区分
CREATE TABLE IF NOT EXISTS claw_long_term (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（聚簇索引，非业务字段）',
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '租户 id（空串=默认空间）',
    user_id     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '用户 id（空串=默认空间）',
    name        VARCHAR(32)  NOT NULL COMMENT '记忆文件标识：AGENT.md | MEMORY.md',
    content     LONGTEXT     DEFAULT NULL COMMENT '文件内容（Markdown）',
    update_time BIGINT       DEFAULT NULL COMMENT '最近更新时间戳（epoch 毫秒）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_long_term (tenant_id, user_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='长期记忆表：AGENT.md / MEMORY.md';

-- ==================== 全链路 trace 表 ====================
-- 步骤级 trace（agent.observability.trace.store=db 时使用）：每次运行写入一行
-- run 标识行（step_type='__run__'）+ 每步一行明细，按 trace_id + step_index 还原链路；
-- 与会话/记忆表同库，多实例共享一份 trace 数据。
CREATE TABLE IF NOT EXISTS claw_trace (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（聚簇索引，非业务字段）',
    tenant_id     VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '租户 id（空串=默认空间）',
    user_id       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '用户 id（空串=默认空间）',
    trace_id      VARCHAR(64)  NOT NULL COMMENT '链路 trace id（贯穿请求日志/落库）',
    session_id    VARCHAR(64)  DEFAULT NULL COMMENT '会话 id',
    agent_id      VARCHAR(64)  DEFAULT NULL COMMENT '主导 Agent id',
    orchestration VARCHAR(32)  DEFAULT NULL COMMENT '实际使用的编排 id',
    model         VARCHAR(64)  DEFAULT NULL COMMENT '使用的模型',
    start_time    BIGINT       DEFAULT NULL COMMENT '执行开始时间戳（epoch 毫秒）',
    duration_ms   BIGINT       DEFAULT NULL COMMENT '执行耗时（毫秒）',
    success       TINYINT(1)   DEFAULT 1 COMMENT '执行是否成功',
    error_code    VARCHAR(32)  DEFAULT NULL COMMENT '失败错误码（成功为空）',
    step_index    INT          DEFAULT NULL COMMENT '步骤序号（0=run 标识行，1..n=明细步骤）',
    step_type     VARCHAR(16)  DEFAULT NULL COMMENT '步骤类型：__run__ / thought / action / observation / info',
    step_content  LONGTEXT     DEFAULT NULL COMMENT '步骤内容（轨迹文本）',
    create_time   BIGINT       DEFAULT NULL COMMENT '写入时间戳（epoch 毫秒）',
    PRIMARY KEY (id),
    KEY idx_trace (trace_id, tenant_id, user_id),
    KEY idx_trace_session (session_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全链路 trace 表：步骤级执行轨迹';

-- ==================== 运行用量摘要表 ====================
-- 每次运行一条运行用量摘要（agent.observability.run-usage-store=db 时使用），
-- 与会话/记忆/trace 同库，多实例共享供 shell /runs 统计。
CREATE TABLE IF NOT EXISTS claw_run_usage (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（聚簇索引，非业务字段）',
    trace_id      VARCHAR(64)  DEFAULT NULL COMMENT '关联的全链路 trace id（GET /trace/{traceId} 可还原步骤明细）',
    session_id    VARCHAR(64)  DEFAULT NULL COMMENT '会话 id',
    agent_id      VARCHAR(64)  DEFAULT NULL COMMENT '主导 Agent id',
    orchestration VARCHAR(32)  DEFAULT NULL COMMENT '实际使用的编排 id',
    model         VARCHAR(64)  DEFAULT NULL COMMENT '使用的模型',
    duration_ms   BIGINT       DEFAULT NULL COMMENT '执行耗时（毫秒）',
    success       TINYINT(1)   DEFAULT 1 COMMENT '执行是否成功',
    steps         INT          DEFAULT 0 COMMENT '步骤条数',
    error_code    VARCHAR(32)  DEFAULT NULL COMMENT '失败错误码（成功为空）',
    create_time   BIGINT       DEFAULT NULL COMMENT '写入时间戳（epoch 毫秒）',
    PRIMARY KEY (id),
    KEY idx_run_create (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行用量摘要表：每次运行一条';
