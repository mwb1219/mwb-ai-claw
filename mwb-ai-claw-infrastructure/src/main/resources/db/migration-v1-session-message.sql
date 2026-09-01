-- ============================================================================
-- T1 · Session messages 拆分表存储 —— 数据迁移脚本
-- 迁移前必须先执行 framework-schema.sql（创建 claw_session_message 表并给 claw_session 加 msg_count）
-- ----------------------------------------------------------------------------
-- 作用：把旧版 claw_session.messages（LONGTEXT，存完整 Session JSON）逐条解析，
--       INSERT 到新表 claw_session_message，消除写放大/读放大/并发覆盖。
-- 要求：MySQL 8.0.4+（依赖 JSON_TABLE）。
-- 幂等：依赖 uk_session_msg 唯一键(tenant_id, user_id, session_id, msg_index)，重复执行自动跳过已迁移行。
-- 说明：迁移后旧 messages 字段已由 schema 移除；如需回滚请先保留旧库备份。
-- ============================================================================

INSERT IGNORE INTO claw_session_message
    (tenant_id, user_id, session_id, msg_index, role, content, parts_json, tool_calls, tool_call_id, archived, create_time)
SELECT
    c.tenant_id,
    c.user_id,
    c.session_id,
    msg_item.idx - 1 AS msg_index,              -- FOR ORDINALITY 从 1 起，索引对齐为 0 起
    JSON_UNQUOTE(JSON_EXTRACT(msg_item.row_json, '$.role'))          AS role,
    JSON_UNQUOTE(JSON_EXTRACT(msg_item.row_json, '$.content'))       AS content,
    JSON_UNQUOTE(JSON_EXTRACT(msg_item.row_json, '$.parts'))         AS parts_json,
    JSON_UNQUOTE(JSON_EXTRACT(msg_item.row_json, '$.toolCalls'))     AS tool_calls,
    JSON_UNQUOTE(JSON_EXTRACT(msg_item.row_json, '$.toolCallId'))    AS tool_call_id,
    0                                                                 AS archived,
    COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(msg_item.row_json, '$.timestamp')) AS BIGINT),
             c.create_time)                                          AS create_time
FROM claw_session c
JOIN JSON_TABLE(
    CAST(c.messages AS JSON),
    '$.messages[*]'
    COLUMNS (
        idx      FOR ORDINALITY,
        row_json JSON  PATH '$'
    )
) AS msg_item
ON TRUE
ORDER BY c.id, msg_item.idx;