package com.mwb.ai.claw.domain.rag.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * 传给索引实现的向量查询。
 */
@Data
public class RagVectorQuery {

    /** 检索范围的知识库列表。 */
    private List<String> knowledgeBaseIds = new ArrayList<>();
    /** 查询向量。 */
    private float[] vector;
    /** 生成向量的模型标识。 */
    private String embeddingModel;
    /** 向量维度。 */
    private int dimensions;
    /** 返回条数上限。 */
    private int topK;
    /** 最低相似度阈值。 */
    private double minScore;
    /** 过滤条件。 */
    private Map<String, String> filters = new LinkedHashMap<>();
}
