package com.mwb.ai.claw.domain.memory.layered.evict;

import com.mwb.ai.claw.domain.memory.layered.spi.PageEvictionPolicy;
import com.mwb.ai.claw.domain.memory.layered.model.EvictionContext;

/**
 * 预算驱动换页策略（默认）：消息总量超预算，或未摘要消息达到块大小的 2 倍时触发换页。
 */
public class TokenBudgetEvictionPolicy implements PageEvictionPolicy {

    @Override
    public boolean shouldEvict(EvictionContext context) {
        if (context.unSummarized() < context.getBlockSize()) {
            return false;
        }
        // 预算溢出（全部消息 token 超记忆区预算）→ 立即换页；否则未摘要消息达到 2 倍块大小时换页
        return context.getTotalTokens() > context.getContextBudget()
                || context.unSummarized() >= context.getBlockSize() * 2;
    }
}
