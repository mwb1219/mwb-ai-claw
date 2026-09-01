package com.mwb.ai.claw.domain.core;

import com.mwb.ai.claw.domain.scope.AgentScope;

import java.util.List;

/**
 * 记忆网关接口：抽象会话持久化能力（依赖倒置）。
 * <p>
 * T1 拆分表后，saveSession 语义变更为「增量写入消息 + 独立更新元数据」，
 * getSession 默认只加载未归档消息（archived=0），按需加载通过 loadRecentMessages 实现。
 */
public interface SessionGateway {

    /**
     * 保存会话：增量写入新消息 + 独立 UPDATE 元数据。
     * 实现方应保证并发安全（msg_index 唯一键防重复，version CAS 防覆盖）。
     */
    void saveSession(Session session);

    /**
     * 加载会话（默认只加载未归档消息，即 archived=0，作为模型工作记忆来源）。
     * 需要全量消息（归档/摘要提炼场景）时显式调用 {@link #loadAllMessages}。
     */
    Session getSession(AgentScope scope, String sessionId);

    /**
     * 加载会话全量消息（含已归档，供前端会话详情 / 历史展示使用）。
     * <p>
     * 与 {@link #getSession} 的读取口径分离：本方法返回含归档的全部原文；getSession 仅返回未归档（活动）消息。
     * default 兜底为 getSession，自定义存储实现若需区分"全量/活动"语义可覆写。
     */
    default Session getSessionFull(AgentScope scope, String sessionId) {
        return getSession(scope, sessionId);
    }

    /**
     * 列出某 scope 下的所有会话（仅含元数据，不含完整消息列表）。
     */
    List<Session> listSessions(AgentScope scope);

    /**
     * 删除会话（同时清理关联的消息、摘要页等派生数据）。
     */
    void deleteSession(AgentScope scope, String sessionId);

    /**
     * 按需加载最近 N 条未归档消息（HOT 区直接 SELECT LIMIT N，避免全量反序列化）。
     *
     * @param scope     会话所属 scope
     * @param sessionId 会话 id
     * @param limit     最多返回条数（<=0 视为不限制）
     * @return 按时间正序排列的消息列表；会话不存在返回空列表
     */
    List<Message> loadRecentMessages(AgentScope scope, String sessionId, int limit);

    /**
     * 加载会话全量消息（含已归档，供归档/摘要提炼使用）。
     *
     * @param scope     会话所属 scope
     * @param sessionId 会话 id
     * @return 按时间正序排列的消息列表；会话不存在返回空列表
     */
    List<Message> loadAllMessages(AgentScope scope, String sessionId);

    /**
     * 标记已归档/摘要的消息（afterTurn/afterSession 提炼成功后调用，让后续 readContext 跳过这些消息）。
     *
     * @param scope     会话所属 scope
     * @param sessionId 会话 id
     * @param fromIndex 要标记的消息起始下标（含，原索引）
     * @param toIndex   要标记的消息结束下标（不含，原索引）
     */
    void markArchived(AgentScope scope, String sessionId, int fromIndex, int toIndex);
}
