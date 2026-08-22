package com.mwb.ai.claw.domain.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * RAG 检索请求。知识库列表为空表示检索全部全局知识库。
 */
@Data
public class RagQuery {

    /** 检索范围的知识库列表；为空表示检索全部全局知识库。 */
    private List<String> knowledgeBaseIds = new ArrayList<>();
    /** 查询文本。 */
    private String text;
    /** 返回条数上限，小于等于 0 时使用配置默认值。 */
    private int topK;
    /** 最低相似度阈值，小于 0 时使用配置默认值。 */
    private double minScore = -1D;
    /** 过滤条件，按元数据精确匹配。 */
    private Map<String, String> filters = new LinkedHashMap<>();
}
