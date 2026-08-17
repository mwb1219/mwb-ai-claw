package com.mwb.ai.claw.domain.llm;

/**
 * Embedding 网关接口：为文本生成向量（Phase 3 向量检索的基础能力，依赖倒置）。
 * <p>
 * 向量检索（VectorMemoryRetriever）依赖此接口：文本 → 向量 → 余弦相似度召回。
 */
public interface EmbeddingGateway {

    /**
     * 将文本编码为向量。
     *
     * @param text 文本
     * @return 向量（维度由实现决定）；文本为空时返回空向量（长度 0）
     */
    float[] embed(String text);
}
