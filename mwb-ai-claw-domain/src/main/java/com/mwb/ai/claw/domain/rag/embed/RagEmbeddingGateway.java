package com.mwb.ai.claw.domain.rag.embed;

import java.util.List;

/**
 * RAG 专用 Embedding SPI，与记忆向量配置和缓存相互独立。
 */
public interface RagEmbeddingGateway {

    /**
     * 对单条文本生成向量。
     *
     * @param text 输入文本
     * @return 文本向量
     */
    float[] embed(String text);

    /**
     * 对多条文本批量生成向量，顺序与输入保持一致。
     *
     * @param texts 文本列表
     * @return 向量列表
     */
    List<float[]> embedBatch(List<String> texts);

    /** 使用的 Embedding 模型标识。 */
    String modelId();

    /** 向量维度；首次写入前未知时返回 0。 */
    int dimensions();
}
