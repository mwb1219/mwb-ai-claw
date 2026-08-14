package com.mwb.ai.claw.domain.memory;

/**
 * 分层记忆配置（绑定 application.yml 的 agent.memory 前缀）。
 */
public class LayeredMemoryConfig {

    /** 是否启用分层记忆 */
    private boolean enabled = true;

    /** 模型上下文窗口（tokens），用于预算计算 */
    private int contextWindowTokens = 65536;

    /** 记忆区占模型窗口比例 */
    private double contextBudgetRatio = 0.6;

    /** System 区（AGENT.md + 事实页）占记忆预算比例 */
    private double promptBudgetRatio = 0.25;

    /** Tools 区占记忆预算比例 */
    private double toolBudgetRatio = 0.25;

    /** 工作记忆：Hot 原文保留的最大消息条数 */
    private int hotWindowSize = 20;

    /** 多少条消息合成一个摘要块 */
    private int summaryBlockSize = 10;

    /** 摘要树最大深度（预留，Phase 1 单级摘要） */
    private int maxSummaryDepth = 3;

    /** 事实写入长期记忆的重要度阈值（0-1） */
    private double importanceThreshold = 0.6;

    /** 检索召回条数 */
    private int topK = 5;

    /** 换页策略：token（预算驱动，默认）| importance（重要度驱动） */
    private String evictionPolicy = "token";

    /** 提炼是否异步执行（线程池串行，不阻塞主对话链路） */
    private boolean synthesisAsync = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    public void setContextWindowTokens(int contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
    }

    public double getContextBudgetRatio() {
        return contextBudgetRatio;
    }

    public void setContextBudgetRatio(double contextBudgetRatio) {
        this.contextBudgetRatio = contextBudgetRatio;
    }

    public double getPromptBudgetRatio() {
        return promptBudgetRatio;
    }

    public void setPromptBudgetRatio(double promptBudgetRatio) {
        this.promptBudgetRatio = promptBudgetRatio;
    }

    public double getToolBudgetRatio() {
        return toolBudgetRatio;
    }

    public void setToolBudgetRatio(double toolBudgetRatio) {
        this.toolBudgetRatio = toolBudgetRatio;
    }

    public int getHotWindowSize() {
        return hotWindowSize;
    }

    public void setHotWindowSize(int hotWindowSize) {
        this.hotWindowSize = hotWindowSize;
    }

    public int getSummaryBlockSize() {
        return summaryBlockSize;
    }

    public void setSummaryBlockSize(int summaryBlockSize) {
        this.summaryBlockSize = summaryBlockSize;
    }

    public int getMaxSummaryDepth() {
        return maxSummaryDepth;
    }

    public void setMaxSummaryDepth(int maxSummaryDepth) {
        this.maxSummaryDepth = maxSummaryDepth;
    }

    public double getImportanceThreshold() {
        return importanceThreshold;
    }

    public void setImportanceThreshold(double importanceThreshold) {
        this.importanceThreshold = importanceThreshold;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public String getEvictionPolicy() {
        return evictionPolicy;
    }

    public void setEvictionPolicy(String evictionPolicy) {
        this.evictionPolicy = evictionPolicy;
    }

    public boolean isSynthesisAsync() {
        return synthesisAsync;
    }

    public void setSynthesisAsync(boolean synthesisAsync) {
        this.synthesisAsync = synthesisAsync;
    }
}
