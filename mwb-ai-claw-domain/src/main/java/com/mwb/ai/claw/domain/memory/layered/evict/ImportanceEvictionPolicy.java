package com.mwb.ai.claw.domain.memory.layered.evict;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.memory.layered.spi.PageEvictionPolicy;
import com.mwb.ai.claw.domain.memory.layered.model.EvictionContext;
import com.mwb.ai.claw.domain.memory.layered.synthesize.MessageImportanceEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 重要度驱动换页策略：低价值消息块提前压缩，高价值消息尽量留在工作记忆。
 * <p>
 * 触发条件（相比预算策略更敏感）：
 * <ul>
 *   <li>未摘要消息达到块大小时，若最旧未摘要块的平均重要度低于阈值 → 触发（低价值优先压缩）；</li>
 *   <li>预算溢出或未摘要消息达到 2 倍块大小时 → 无条件触发。</li>
 * </ul>
 */
public class ImportanceEvictionPolicy implements PageEvictionPolicy {

    @Override
    public boolean shouldEvict(EvictionContext context) {
        if (context.unSummarized() < context.getBlockSize()) {
            return false;
        }
        if (context.getTotalTokens() > context.getContextBudget()
                || context.unSummarized() >= context.getBlockSize() * 2) {
            return true;
        }
        // 最旧未摘要块的 user 消息重要度低于阈值 → 低价值话题尽早压缩；
        // 高价值话题（含重要约束/偏好等）尽量留在工作记忆
        List<Message> oldestBlock = oldestUnsummarizedBlock(context);
        return MessageImportanceEstimator.maxUserImportance(oldestBlock) < context.getImportanceThreshold();
    }

    /** 最旧的未摘要消息块（用于评估其价值） */
    private List<Message> oldestUnsummarizedBlock(EvictionContext context) {
        List<Message> all = context.getAll();
        int start = context.getLastSummarized();
        int end = Math.min(start + context.getBlockSize(), all.size());
        return new ArrayList<>(all.subList(Math.max(start, 0), end));
    }
}
