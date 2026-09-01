package com.mwb.ai.claw.domain.memory.layered;

import java.time.Duration;

/**
 * 分层记忆配置（绑定 application.yml 的 agent.memory 前缀）。
 */
public class LayeredMemoryConfig {

    /** 记忆策略：layered（分层记忆，默认）| simple（极简记忆，仅 HOT 原文）| 自定义 Bean 名称 */
    private String strategy = "layered";

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

    /** 检索器类型：keyword（关键词，默认）| vector（向量）| hybrid（关键词+向量 RRF 融合） */
    private String retriever = "keyword";

    /** 向量检索：是否启用向量索引（embedding 未配置时自动降级为关键词检索） */
    private boolean vectorEnabled = false;

    /** 向量检索：Embedding 模型名（OpenAI 兼容，如 text-embedding-3-small；留空则尝试用主模型 baseUrl） */
    private String embeddingModel = "";

    /** 向量检索：Embedding Base URL（留空则继承 agent.base-url） */
    private String embeddingBaseUrl = "";

    /** 向量检索：Embedding API Key（留空则继承 agent.api-key） */
    private String embeddingApiKey = "";

    /** 向量检索：本地降级向量维度（无 embedding 服务时用确定性哈希向量兜底） */
    private int vectorDimensions = 256;

    /** 档案 RAG：会话结束后是否把会话原文归档为 ARCHIVE 页（跨会话可检索） */
    private boolean archiveEnabled = true;

    /** 归档保留最近 N 条消息不归档不标记（0=使用 hot-window-size 兜底；会话进行中最新原文始终保留在未归档区，供 Hot 工作记忆与前端展示） */
    private int archiveKeepRecent = 0;

    /** 会话闲置多久后收敛剩余热窗（单位如 30m；仅当距离最后一次会话活动超过该时长时才把热窗整体归档+事实收敛，避免长会话无界保留；null/0=不启用空闲收敛） */
    private Duration archiveIdleTimeout = Duration.ofMinutes(30);

    /** 块 token 数低于该值时只保留其摘要、不归档全文（0=不限制，始终归档）；用于过滤低价值块的全文归档 */
    private int archiveMinTokens = 0;

    /** 多 Agent 共享：readContext 时是否自动检索其他会话的档案/摘要/事实并换入（共享记忆） */
    private boolean sharedRetrieve = true;

    // ==================== Phase 4：成本优化 ====================

    /** 小模型提炼：提炼（摘要/事实提取）专用模型名（留空继承主模型，可用更便宜的模型降低成本） */
    private String synthesizerModel = "";

    /** 小模型提炼：Base URL（留空继承 agent.base-url） */
    private String synthesizerBaseUrl = "";

    /** 小模型提炼：API Key（留空继承 agent.api-key） */
    private String synthesizerApiKey = "";

    /** 提炼缓存容量：按输入内容哈希缓存 summarize/extract 结果，避免重复调 LLM（<=0 关闭缓存） */
    private int synthesisCacheSize = 50;

    /** 提炼缓存实现：auto（跟随 agent.storage.type：file→local，db→redis）| local（JVM 内存 LRU）| redis（分布式，生产多实例推荐） */
    private String synthesisCacheType = "auto";

    /** 提炼缓存 Redis TTL（秒）：仅在 type=redis 时生效，默认 1 小时；LLM 提炼结果相对稳定，TTL 到期后自然重建即可 */
    private int synthesisCacheTtlSeconds = 3600;

    /** 提炼缓存 Redis 连接串（type=redis/auto+db 且未全局配置 spring.data.redis 时使用，默认复用会话锁 redisUri 兜底）；留空则复用 spring.data.redis 或 lock redisUri */
    private String synthesisCacheRedisUri = "";

    /** 提炼缓存 Redis key 前缀（type=redis 时生效，默认 claw:syn:；多租户/多环境共享 Redis 时可前缀隔离命名空间） */
    private String synthesisCacheRedisKeyPrefix = "claw:syn:";

    // ==================== Phase 1：提炼任务队列 ====================

    /** 提炼任务队列类型：auto（默认，跟随 synthesis-cache-type 推断）| local | redis | jdbc */
    private String synthesisQueueType = "auto";

    /** 合成锁 TTL（秒）：仅在 queue-type=redis 时生效，默认 10min；LLM 长上下文时可放大 */
    private int synthesisLockTtlSeconds = 600;

    /** 合成锁 watchdog 续期间隔（秒）：默认 1/3 TTL */
    private int synthesisLockWatchdogIntervalSeconds = 200;

    /** 是否启用"保留最新提交、丢弃旧等待"的去重策略（默认 true） */
    private boolean synthesisDropOldPending = true;

    // ==================== Phase 2：无锁 CAS 配置 ====================

    /**
     * Phase 2 CAS claim 最大重试次数（默认 3）。
     * 仅在 queue-type=lockfree 时生效；Phase 1 LockMemorySynthesisDispatcher 不使用此配置。
     */
    private int synthesisClaimMaxRetries = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
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

    public String getRetriever() {
        return retriever;
    }

    public void setRetriever(String retriever) {
        this.retriever = retriever;
    }

    public boolean isVectorEnabled() {
        return vectorEnabled;
    }

    public void setVectorEnabled(boolean vectorEnabled) {
        this.vectorEnabled = vectorEnabled;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    public void setEmbeddingBaseUrl(String embeddingBaseUrl) {
        this.embeddingBaseUrl = embeddingBaseUrl;
    }

    public String getEmbeddingApiKey() {
        return embeddingApiKey;
    }

    public void setEmbeddingApiKey(String embeddingApiKey) {
        this.embeddingApiKey = embeddingApiKey;
    }

    public int getVectorDimensions() {
        return vectorDimensions;
    }

    public void setVectorDimensions(int vectorDimensions) {
        this.vectorDimensions = vectorDimensions;
    }

    public boolean isArchiveEnabled() {
        return archiveEnabled;
    }

    public void setArchiveEnabled(boolean archiveEnabled) {
        this.archiveEnabled = archiveEnabled;
    }

    public int getArchiveKeepRecent() {
        return archiveKeepRecent;
    }

    public void setArchiveKeepRecent(int archiveKeepRecent) {
        this.archiveKeepRecent = archiveKeepRecent;
    }

    public Duration getArchiveIdleTimeout() {
        return archiveIdleTimeout;
    }

    public void setArchiveIdleTimeout(Duration archiveIdleTimeout) {
        this.archiveIdleTimeout = archiveIdleTimeout;
    }

    public int getArchiveMinTokens() {
        return archiveMinTokens;
    }

    public void setArchiveMinTokens(int archiveMinTokens) {
        this.archiveMinTokens = archiveMinTokens;
    }

    public boolean isSharedRetrieve() {
        return sharedRetrieve;
    }

    public void setSharedRetrieve(boolean sharedRetrieve) {
        this.sharedRetrieve = sharedRetrieve;
    }

    public String getSynthesizerModel() {
        return synthesizerModel;
    }

    public void setSynthesizerModel(String synthesizerModel) {
        this.synthesizerModel = synthesizerModel;
    }

    public String getSynthesizerBaseUrl() {
        return synthesizerBaseUrl;
    }

    public void setSynthesizerBaseUrl(String synthesizerBaseUrl) {
        this.synthesizerBaseUrl = synthesizerBaseUrl;
    }

    public String getSynthesizerApiKey() {
        return synthesizerApiKey;
    }

    public void setSynthesizerApiKey(String synthesizerApiKey) {
        this.synthesizerApiKey = synthesizerApiKey;
    }

    public int getSynthesisCacheSize() {
        return synthesisCacheSize;
    }

    public void setSynthesisCacheSize(int synthesisCacheSize) {
        this.synthesisCacheSize = synthesisCacheSize;
    }

    public String getSynthesisCacheType() {
        return synthesisCacheType;
    }

    public void setSynthesisCacheType(String synthesisCacheType) {
        this.synthesisCacheType = synthesisCacheType;
    }

    public int getSynthesisCacheTtlSeconds() {
        return synthesisCacheTtlSeconds;
    }

    public void setSynthesisCacheTtlSeconds(int synthesisCacheTtlSeconds) {
        this.synthesisCacheTtlSeconds = synthesisCacheTtlSeconds;
    }

    public String getSynthesisCacheRedisUri() {
        return synthesisCacheRedisUri;
    }

    public void setSynthesisCacheRedisUri(String synthesisCacheRedisUri) {
        this.synthesisCacheRedisUri = synthesisCacheRedisUri;
    }

    public String getSynthesisCacheRedisKeyPrefix() {
        return synthesisCacheRedisKeyPrefix;
    }

    public void setSynthesisCacheRedisKeyPrefix(String synthesisCacheRedisKeyPrefix) {
        this.synthesisCacheRedisKeyPrefix = synthesisCacheRedisKeyPrefix;
    }

    public String getSynthesisQueueType() {
        return synthesisQueueType;
    }

    public void setSynthesisQueueType(String synthesisQueueType) {
        this.synthesisQueueType = synthesisQueueType;
    }

    public int getSynthesisLockTtlSeconds() {
        return synthesisLockTtlSeconds;
    }

    public void setSynthesisLockTtlSeconds(int synthesisLockTtlSeconds) {
        this.synthesisLockTtlSeconds = synthesisLockTtlSeconds;
    }

    public int getSynthesisLockWatchdogIntervalSeconds() {
        return synthesisLockWatchdogIntervalSeconds;
    }

    public void setSynthesisLockWatchdogIntervalSeconds(int synthesisLockWatchdogIntervalSeconds) {
        this.synthesisLockWatchdogIntervalSeconds = synthesisLockWatchdogIntervalSeconds;
    }

    public boolean isSynthesisDropOldPending() {
        return synthesisDropOldPending;
    }

    public void setSynthesisDropOldPending(boolean synthesisDropOldPending) {
        this.synthesisDropOldPending = synthesisDropOldPending;
    }

    public int getSynthesisClaimMaxRetries() {
        return synthesisClaimMaxRetries;
    }

    public void setSynthesisClaimMaxRetries(int synthesisClaimMaxRetries) {
        this.synthesisClaimMaxRetries = synthesisClaimMaxRetries;
    }
}
