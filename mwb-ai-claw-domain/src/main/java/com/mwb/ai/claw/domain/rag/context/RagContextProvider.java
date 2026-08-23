package com.mwb.ai.claw.domain.rag;

import java.util.List;

/**
 * 将独立 RAG 检索结果转换为 Agent 上下文的薄适配端口。
 */
public interface RagContextProvider {

    /**
     * 基于查询与知识库列表构建注入 Agent 上下文的知识内容。
     *
     * @param query            用户查询文本
     * @param knowledgeBaseIds 检索范围的知识库列表
     * @return 拼接后的知识库参考文本；无命中或未启用时返回空串
     */
    String buildContext(String query, List<String> knowledgeBaseIds);
}
