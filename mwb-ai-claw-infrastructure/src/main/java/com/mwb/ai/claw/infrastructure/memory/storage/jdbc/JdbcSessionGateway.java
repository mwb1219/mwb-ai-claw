package com.mwb.ai.claw.infrastructure.memory.storage.jdbc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.MessageRole;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.core.SessionGateway;
import com.mwb.ai.claw.domain.core.SessionStatus;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.util.JsonUtils;

/**
 * JDBC 版会话存储（agent.storage.type=jdbc）：会话元数据落 claw_session 表，
 * 消息逐条追加落 claw_session_message 表（消除 messages LONGTEXT 的写放大/读放大/并发覆盖）。
 * <p>
 * 写路径：saveSession 先增量 INSERT 新消息（msg_index 唯一键防并发重复），
 * 再独立 UPDATE 元数据 + SET msg_count = (SELECT COUNT ... FROM claw_session_message)。
 * 读路径：getSession 默认只加载 archived=0 的未归档消息（HOT 区直接按需加载），
 * loadRecentMessages 支持 LIMIT N 按需加载，loadAllMessages 加载全量供提炼使用。
 * <p>
 * tenant_id / user_id 用空字符串 '' 表示默认空间（MySQL 主键列不允许 NULL，与文件模式 legacy 语义对齐）。
 */
public class JdbcSessionGateway implements SessionGateway {

    private static final Logger log = LoggerFactory.getLogger(JdbcSessionGateway.class);

    private final JdbcTemplate jdbc;

    public JdbcSessionGateway(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== 写路径 ====================

    @Override
    public void saveSession(Session session) {
        String tenantId = norm(session.getTenantId());
        String userId = norm(session.getUserId());
        String sessionId = session.getSessionId();
        ScopeClause where = scopeWhere(tenantId, userId);
        Long now = System.currentTimeMillis();
        String status = session.getStatus() == null ? null : session.getStatus().name();

        // 1. 单条 UPSERT 会话元数据（INSERT ... ON DUPLICATE KEY UPDATE）
        //    消除「SELECT COUNT + INSERT/UPDATE」的竞态窗口：两个实例同时入库同一 session_id 时，
        //    MySQL 借 uk_session(tenant,user,session) 唯一键合并为一次写入，后写者只更新元数据而不报错。
        //    version 用原子自增（version = version + 1）做乐观版本，避免并发丢失更新。
        String upsertSql = "INSERT INTO claw_session "
                + "(tenant_id, user_id, session_id, agent_id, title, status, version, msg_count, create_time, update_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE "
                + "agent_id=VALUES(agent_id), title=VALUES(title), status=VALUES(status), "
                + "version=version+1, update_time=VALUES(update_time)";
        List<Object> metaArgs = new ArrayList<>();
        metaArgs.add(tenantId);
        metaArgs.add(userId);
        metaArgs.add(sessionId);
        metaArgs.add(session.getAgentId());
        metaArgs.add(session.getTitle());
        metaArgs.add(status);
        metaArgs.add(session.getVersion());
        metaArgs.add(session.getMessages() == null ? 0 : session.getMessages().size());
        metaArgs.add(session.getCreateTime());
        metaArgs.add(now);
        jdbc.update(upsertSql, metaArgs.toArray());

        // 2. 读取当前最大消息序号，作为下一个 msg_index 的起点（MAX+1 消除 msg_count 滞后的竞态窗口）
        Integer maxIdx = jdbc.queryForObject(
                "SELECT COALESCE(MAX(msg_index), -1) + 1 FROM claw_session_message WHERE session_id = ? AND " + where.sql,
                Integer.class, buildArgs(sessionId, where.args).toArray());
        int startIndex = maxIdx == null ? 0 : maxIdx;

        // 3. 增量写入本轮尚未入库的新消息。
        //    getSession（读路径）只返回未归档（archived=0）的子集，因此 session.messages 并非
        //    从 0 起始的连续全量列表，不能依赖数组下标定位增量。改为以「msg_index 是否已存在于库」判定：
        //    已入库（>=0 且命中）的跳过（本次仅做元数据更新），新增（默认 -1 或不在库中）按其出现顺序从
        //    startIndex 起连续分配 msg_index 逐条 INSERT IGNORE（唯一键防并发重复）。
        List<Message> messages = session.getMessages();
        if (messages != null) {
            Set<Integer> existingIdx = new HashSet<>(jdbc.queryForList(
                    "SELECT msg_index FROM claw_session_message WHERE session_id = ? AND " + where.sql,
                    Integer.class, buildArgs(sessionId, where.args).toArray()));
            List<Message> newMessages = new ArrayList<>();
            for (Message m : messages) {
                if (m.getMsgIndex() >= 0 && existingIdx.contains(m.getMsgIndex())) {
                    continue;
                }
                newMessages.add(m);
            }
            if (!newMessages.isEmpty()) {
                insertMessages(tenantId, userId, sessionId, newMessages, startIndex);
            }
        }

        // 4. 用子查询精确同步 msg_count（与消息表一致，消除元数据计数滞后的窗口）
        String syncMsgCount = "UPDATE claw_session SET msg_count = "
                + "(SELECT c FROM (SELECT COUNT(*) c FROM claw_session_message WHERE session_id=? AND " + where.sql + ") t) "
                + "WHERE session_id = ? AND " + where.sql;
        List<Object> syncArgs = new ArrayList<>();
        syncArgs.add(sessionId);
        syncArgs.addAll(where.args);
        syncArgs.add(sessionId);
        syncArgs.addAll(where.args);
        jdbc.update(syncMsgCount, syncArgs.toArray());
    }

    /** 批量 INSERT 消息（忽略已存在的 msg_index，防并发重复） */
    private void insertMessages(String tenantId, String userId, String sessionId,
                                List<Message> messages, int startIndex) {
        String sql = "INSERT IGNORE INTO claw_session_message "
                + "(tenant_id, user_id, session_id, msg_index, role, content, parts_json, tool_calls, tool_call_id, archived, create_time) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            int msgIndex = startIndex + i;
            m.setMsgIndex(msgIndex);
            jdbc.update(sql,
                    tenantId, userId, sessionId, msgIndex,
                    m.getRole() == null ? null : m.getRole().name(),
                    m.getContent(),
                    m.getParts() == null ? null : JsonUtils.toJson(m.getParts()),
                    m.getToolCalls() == null ? null : JsonUtils.toJson(m.getToolCalls()),
                    m.getToolCallId(),
                    0, // archived=0
                    m.getTimestamp() > 0 ? m.getTimestamp() : System.currentTimeMillis());
        }
    }

    // ==================== 读路径 ====================

    @Override
    public Session getSession(AgentScope scope, String sessionId) {
        ScopeClause where = scopeWhere(scope);
        // 读元数据
        String sql = "SELECT session_id, agent_id, title, status, version, msg_count, create_time, update_time "
                + "FROM claw_session WHERE session_id = ? AND " + where.sql;
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(where.args);
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());
        if (rows.isEmpty()) {
            return null;
        }
        Session session = rowToSession(rows.get(0), scope);
        // 默认只加载未归档消息（archived=0）
        session.setMessages(loadMessagesInternal(scope, sessionId, false, 0));
        return session;
    }

    @Override
    public Session getSessionFull(AgentScope scope, String sessionId) {
        ScopeClause where = scopeWhere(scope);
        String sql = "SELECT session_id, agent_id, title, status, version, msg_count, create_time, update_time "
                + "FROM claw_session WHERE session_id = ? AND " + where.sql;
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(where.args);
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args.toArray());
        if (rows.isEmpty()) {
            return null;
        }
        Session session = rowToSession(rows.get(0), scope);
        // 全量语义：加载含归档的全部原文（供前端会话详情 / 历史展示）
        session.setMessages(loadMessagesInternal(scope, sessionId, true, 0));
        return session;
    }

    @Override
    public List<Session> listSessions(AgentScope scope) {
        ScopeClause where = scopeWhere(scope);
        String sql = "SELECT session_id, agent_id, title, status, version, msg_count, create_time, update_time "
                + "FROM claw_session WHERE " + where.sql + " ORDER BY update_time DESC";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, where.args.toArray());
        List<Session> sessions = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            sessions.add(rowToSession(row, scope));
        }
        return sessions;
    }

    @Override
    public List<Message> loadRecentMessages(AgentScope scope, String sessionId, int limit) {
        return loadMessagesInternal(scope, sessionId, false, limit);
    }

    @Override
    public List<Message> loadAllMessages(AgentScope scope, String sessionId) {
        return loadMessagesInternal(scope, sessionId, true, 0);
    }

    /**
     * 从 claw_session_message 加载消息。
     *
     * @param includeArchived true=加载全量（含已归档），false=只加载 archived=0
     * @param limit           <=0 不限制；>0 限制返回条数（ORDER BY msg_index DESC LIMIT N 后正序反转）
     */
    private List<Message> loadMessagesInternal(AgentScope scope, String sessionId,
                                               boolean includeArchived, int limit) {
        ScopeClause where = scopeWhere(scope);
        StringBuilder sql = new StringBuilder(
                "SELECT msg_index, role, content, parts_json, tool_calls, tool_call_id, archived, create_time FROM claw_session_message ");
        sql.append("WHERE session_id = ? AND ").append(where.sql);
        if (!includeArchived) {
            sql.append(" AND archived = 0");
        }
        sql.append(" ORDER BY msg_index ");
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(where.args);

        if (limit > 0) {
            // 先反序 LIMIT N 取最近 N 条，再反转回正序
            StringBuilder sub = new StringBuilder(
                    "SELECT msg_index, role, content, parts_json, tool_calls, tool_call_id, archived, create_time FROM (");
            sub.append(sql).append(" ORDER BY msg_index DESC LIMIT ?) t ORDER BY create_time ASC");
            args.add(limit);
            sql = sub;
        } else {
            sql.append("ASC");
        }

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Message> messages = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            messages.add(rowToMessage(row));
        }
        return messages;
    }

    @Override
    public void deleteSession(AgentScope scope, String sessionId) {
        ScopeClause where = scopeWhere(scope);
        // 先删消息（外键约束倒序）
        jdbc.update("DELETE FROM claw_session_message WHERE session_id = ? AND " + where.sql,
                buildArgs(sessionId, where.args));
        // 再删会话
        jdbc.update("DELETE FROM claw_session WHERE session_id = ? AND " + where.sql,
                buildArgs(sessionId, where.args));
    }

    @Override
    public void markArchived(AgentScope scope, String sessionId, int fromIndex, int toIndex) {
        ScopeClause where = scopeWhere(scope);
        String sql = "UPDATE claw_session_message SET archived = 1 "
                + "WHERE session_id = ? AND " + where.sql + " AND msg_index >= ? AND msg_index < ? AND archived = 0";
        List<Object> args = buildArgs(sessionId, where.args);
        args.add(fromIndex);
        args.add(toIndex);
        jdbc.update(sql, args.toArray());
    }

    // ==================== 行映射 ====================

    private Session rowToSession(Map<String, Object> row, AgentScope scope) {
        Session s = new Session();
        s.setSessionId((String) row.get("session_id"));
        s.setAgentId((String) row.get("agent_id"));
        s.setTitle((String) row.get("title"));
        String status = (String) row.get("status");
        if (status != null) {
            try {
                s.setStatus(SessionStatus.valueOf(status));
            } catch (Exception ignore) {
                // 非法值忽略
            }
        }
        Object ver = row.get("version");
        s.setVersion(ver instanceof Number ? ((Number) ver).longValue() : 0);
        Object msgCount = row.get("msg_count");
        s.setMsgCount(msgCount instanceof Number ? ((Number) msgCount).intValue() : 0);
        Object ct = row.get("create_time");
        s.setCreateTime(ct instanceof Number ? ((Number) ct).longValue() : 0);
        Object ut = row.get("update_time");
        s.setUpdateTime(ut instanceof Number ? ((Number) ut).longValue() : 0);
        s.setTenantId(scope == null ? null : scope.getTenantId());
        s.setUserId(scope == null ? null : scope.getUserId());
        return s;
    }

    private Message rowToMessage(Map<String, Object> row) {
        Message m = new Message();
        String role = (String) row.get("role");
        if (role != null) {
            try {
                m.setRole(MessageRole.valueOf(role));
            } catch (Exception ignore) {
                // 非法值忽略
            }
        }
        m.setContent((String) row.get("content"));
        String partsJson = (String) row.get("parts_json");
        if (partsJson != null && !partsJson.isEmpty()) {
            try {
                m.setParts(JsonUtils.fromJsonList(partsJson, com.mwb.ai.claw.domain.llm.ContentPart.class));
            } catch (Exception e) {
                log.debug("解析 parts_json 失败: {}", e.getMessage());
            }
        }
        String toolCallsJson = (String) row.get("tool_calls");
        if (toolCallsJson != null && !toolCallsJson.isEmpty()) {
            try {
                m.setToolCalls(JsonUtils.fromJsonList(toolCallsJson, com.mwb.ai.claw.domain.llm.ToolCall.class));
            } catch (Exception e) {
                log.debug("解析 tool_calls 失败: {}", e.getMessage());
            }
        }
        m.setToolCallId((String) row.get("tool_call_id"));
        Object mi = row.get("msg_index");
        m.setMsgIndex(mi instanceof Number ? ((Number) mi).intValue() : -1);
        Object ar = row.get("archived");
        if (ar instanceof Boolean) {
            // MySQL tinyint(1) 经 Connector/J 默认映射为 Boolean
            m.setArchived((Boolean) ar);
        } else if (ar instanceof Number) {
            m.setArchived(((Number) ar).intValue() == 1);
        } else {
            m.setArchived(false);
        }
        Object ct = row.get("create_time");
        m.setTimestamp(ct instanceof Number ? ((Number) ct).longValue() : 0);
        return m;
    }

    // ==================== 工具 ====================

    private List<Object> buildArgs(Object first, List<Object> rest) {
        List<Object> args = new ArrayList<>();
        args.add(first);
        args.addAll(rest);
        return args;
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
