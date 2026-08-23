package com.mwb.ai.claw.domain.memory.model;

/**
 * 记忆预算：基于配置计算 LLM 上下文窗口内的 token 分配。
 * <p>
 * 预算模型：contextBudget = 窗口 × contextBudgetRatio；
 * 其内部分配 System 区（AGENT.md + 事实页）、Tools 区、Memory 区（Hot + Summary + Retrieved）。
 */
public class MemoryBudget {

    private final int contextBudget;
    private final int systemBudget;
    private final int toolsBudget;
    private final int memoryBudget;
    private final LayeredMemoryConfig config;

    public MemoryBudget(LayeredMemoryConfig config) {
        this.config = config;
        this.contextBudget = (int) (config.getContextWindowTokens() * config.getContextBudgetRatio());
        this.systemBudget = (int) (contextBudget * config.getPromptBudgetRatio());
        this.toolsBudget = (int) (contextBudget * config.getToolBudgetRatio());
        this.memoryBudget = contextBudget - systemBudget - toolsBudget;
    }

    /** 记忆区总预算（system + tools 之外的部分） */
    public int getContextBudget() {
        return contextBudget;
    }

    public int getSystemBudget() {
        return systemBudget;
    }

    public int getToolsBudget() {
        return toolsBudget;
    }

    public int getMemoryBudget() {
        return memoryBudget;
    }

    public LayeredMemoryConfig getConfig() {
        return config;
    }
}
