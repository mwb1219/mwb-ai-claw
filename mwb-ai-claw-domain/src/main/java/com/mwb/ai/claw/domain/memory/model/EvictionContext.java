package com.mwb.ai.claw.domain.memory;

import com.mwb.ai.claw.domain.core.Message;

import java.util.List;

/**
 * 换页上下文：换页策略评估所需的只读快照（消息列表 / 预算 / 边界 / 阈值）。
 */
public class EvictionContext {

    /** 当前会话全部消息（按时间顺序） */
    private final List<Message> all;

    /** 已摘要消息边界（摘要页 blockEnd 最大值） */
    private final int lastSummarized;

    /** 全部消息估算 token 总量 */
    private final int totalTokens;

    /** 记忆区上下文预算（tokens） */
    private final int contextBudget;

    /** 摘要块大小 */
    private final int blockSize;

    /** 重要度阈值（importance 策略参考） */
    private final double importanceThreshold;

    public EvictionContext(List<Message> all, int lastSummarized, int totalTokens,
                           int contextBudget, int blockSize, double importanceThreshold) {
        this.all = all;
        this.lastSummarized = lastSummarized;
        this.totalTokens = totalTokens;
        this.contextBudget = contextBudget;
        this.blockSize = blockSize;
        this.importanceThreshold = importanceThreshold;
    }

    /** 未摘要消息条数 */
    public int unSummarized() {
        return all.size() - lastSummarized;
    }

    public List<Message> getAll() {
        return all;
    }

    public int getLastSummarized() {
        return lastSummarized;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public int getContextBudget() {
        return contextBudget;
    }

    public int getBlockSize() {
        return blockSize;
    }

    public double getImportanceThreshold() {
        return importanceThreshold;
    }
}
