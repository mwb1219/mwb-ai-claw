package com.mwb.ai.claw.domain.rag.config;

import lombok.Data;

/**
 * 独立 RAG 配置，对应 {@code agent.rag.*}。
 */
@Data
public class RagConfig {

    /** 总开关，默认关闭以保持现有 Agent 行为。 */
    private boolean enabled = false;

    /** 索引实现类型，内置 local / pgvector。 */
    private String provider = "local";

    /** 本地索引实现配置。 */
    private LocalConfig local = new LocalConfig();

    /** PGVector 索引实现配置（provider=pgvector 时生效）。 */
    private PgVectorConfig pgvector = new PgVectorConfig();

    /** 知识库 API 层访问控制（可选，默认关闭，不改变全局共享检索语义）。 */
    private AccessConfig access = new AccessConfig();

    /** 容量与配额管理（0 表示不限制）。 */
    private CapacityConfig capacity = new CapacityConfig();

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
    public static class PgVectorConfig {
        /** 索引表名。 */
        private String table = "rag_index_entries";
        /** 表所在 schema。 */
        private String schema = "public";
        /** 向量索引类型：ivfflat（默认，创建快）| hnsw（召回更准，写入更重）。 */
        private String indexType = "ivfflat";
        /** 相似度算子：vector_cosine_ops（默认）| vector_l2_ops | vector_ip_ops。 */
        private String similarity = "vector_cosine_ops";
    }

    @Data
    public static class AccessConfig {
        /** 是否启用知识库 API 层访问控制（默认关闭，关闭时全部放行、保持全局共享语义）。 */
        private boolean enabled = false;
    }

    @Data
    public static class CapacityConfig {
        /** 单个知识库最大文档数，0=不限制。 */
        private int maxDocumentsPerKnowledgeBase = 0;
        /** 单个文档最大分块数，0=不限制。 */
        private int maxChunksPerDocument = 0;
        /** 单个文档解析后文本最大字符数，0=不限制。 */
        private int maxDocumentChars = 0;
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
        /** 单次 HTTP 请求的最大文本条数（模型侧批量上限，如阿里云 MaaS 为 20）；由 Gateway 内部分批保证。 */
        private int maxBatchSize = 16;
    }

    @Data
    public static class ContextConfig {
        /** 单次注入 system prompt 的知识内容字符上限。 */
        private int maxChars = 8000;
    }
}
