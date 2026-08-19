package com.mwb.ai.claw.domain.memory;

import java.util.List;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 记忆提炼器接口：摘要生成 / 事实提取 / 合并去重（依赖 LLM 能力）。
 */
public interface MemorySynthesizer {

    /**
     * 将一段历史消息压缩为摘要文本。
     *
     * @param scope 租户/用户维度（缓存隔离，异步提炼任务显式传参）
     * @param block 消息块（按时间顺序）
     * @return 摘要文本；失败返回 null（调用方忽略，不阻塞对话）
     */
    String summarizeBlock(AgentScope scope, List<Message> block);

    /**
     * 从消息中提取跨会话值得记住的事实。
     *
     * @param scope    租户/用户维度（缓存隔离，异步提炼任务显式传参）
     * @param messages 消息列表
     * @return 事实页列表（重要度已评分，未过滤）
     */
    List<MemoryPage> extractFacts(AgentScope scope, List<Message> messages);

    /**
     * 合并新事实与已有事实（同 key 去重 + 冲突合并）。
     * <p>
     * 规则：保留重要度更高者，重要度相同保留信息更全者；版本号自增，时间戳保留最新。
     *
     * @param existing 已有事实（同 key 的旧值，可为 null）
     * @param fresh    新事实
     * @return 合并后保留的事实
     */
    MemoryPage mergeFact(MemoryPage existing, MemoryPage fresh);
}
