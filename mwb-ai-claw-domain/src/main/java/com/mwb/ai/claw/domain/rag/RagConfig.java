package com.mwb.ai.claw.domain.rag;

import lombok.Data;

/**
 * 独立 RAG 配置，对应 {@code agent.rag.*}。
 */
@Data
public class RagConfig {

    /** 总开关，默认关闭以保持现有 Agent 行为。 */
    private boolean enabled = false;

    /** 索引实现类型，内置 local。 */
    private String provider = "local";

    /** 本地索引实现配置。 */
    private LocalConfig local = new LocalConfig();

    /** 写入阶段（解析、切分、向量化）配置。 */
    private IngestionConfig ingestion = new IngestionConfig();

    /** 检索阶段配置。 */
    private RetrievalConfig retrieval = new RetrievalConfig();

    /** Embedding 模型配置。 */
    private EmbeddingConfig embedding = new EmbeddingConfig();

    /** Agent 上下文注入配置。 */
    private ContextConfig context = new ContextConfig();

    @Data
    public static class LocalConfig {
        /** 默认 {@code ${user.dir}/.agent/rag}。 */
        private String dir = "";
    }

    @Data
    public static class IngestionConfig {
        /** 单块文本长度上限（字符）。 */
        private int chunkSize = 500;
        /** 相邻分块的重叠长度（字符）。 */
        private int chunkOverlap = 50;
        /** 批量向量化的单批文本条数。 */
        private int embeddingBatchSize = 32;
    }

    @Data
    public static class RetrievalConfig {
        /** 默认返回的命中条数。 */
        private int topK = 5;
        /** 默认最低相似度阈值。 */
        private double minScore = 0.2D;
        /** 检索上下文是否必须显式指定知识库。 */
        private boolean requireKnowledgeBaseId = false;
    }

    @Data
    public static class EmbeddingConfig {
        /** 模型名称，为空时使用服务端默认模型。 */
        private String model = "";
        /** Embedding API 地址。 */
        private String baseUrl = "https://api.openai.com/v1";
        /** API 密钥。 */
        private String apiKey = "";
        /** 向量维度，0 表示首次写入时由模型响应确定。 */
        private int dimensions = 0;
    }

    @Data
    public static class ContextConfig {
        /** 单次注入 system prompt 的知识内容字符上限。 */
        private int maxChars = 8000;
    }
}
