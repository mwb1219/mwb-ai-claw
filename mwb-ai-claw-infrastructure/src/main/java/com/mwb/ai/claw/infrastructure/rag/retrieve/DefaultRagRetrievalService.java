package com.mwb.ai.claw.infrastructure.rag.retrieve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.embed.RagEmbeddingGateway;
import com.mwb.ai.claw.domain.rag.model.RagDocument;
import com.mwb.ai.claw.domain.rag.model.RagQuery;
import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.domain.rag.model.RagVectorQuery;
import com.mwb.ai.claw.domain.rag.retrieve.RagReranker;
import com.mwb.ai.claw.domain.rag.retrieve.RagRetrievalService;
import com.mwb.ai.claw.domain.rag.store.RagDocumentStore;
import com.mwb.ai.claw.domain.rag.store.RagIndexStore;
import com.mwb.ai.claw.infrastructure.rag.store.RagFileSupport;

/**
 * 默认 RAG 向量检索服务。
 */
public class DefaultRagRetrievalService implements RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRagRetrievalService.class);

    private final RagEmbeddingGateway embeddingGateway;
    private final RagIndexStore indexStore;
    private final RagDocumentStore documentStore;
    private final RagReranker reranker;
    private final RagConfig.RetrievalConfig config;

    public DefaultRagRetrievalService(RagEmbeddingGateway embeddingGateway,
                                      RagIndexStore indexStore,
                                      RagDocumentStore documentStore,
                                      RagReranker reranker,
                                      RagConfig config) {
        this.embeddingGateway = embeddingGateway;
        this.indexStore = indexStore;
        this.documentStore = documentStore;
        this.reranker = reranker;
        this.config = config.getRetrieval();
    }

    @Override
    public List<RagSearchResult> retrieve(RagQuery query) {
        validate(query);
        int topK = query.getTopK() > 0 ? query.getTopK() : config.getTopK();
        if (topK <= 0) {
            throw new IllegalArgumentException("RAG topK 必须大于 0");
        }
        double minScore = query.getMinScore() >= 0 ? query.getMinScore() : config.getMinScore();
        if (Double.isNaN(minScore) || minScore < -1D || minScore > 1D) {
            throw new IllegalArgumentException("RAG minScore 必须在 [-1, 1] 范围内");
        }
        String embeddingModel = embeddingGateway.modelId();
        if (embeddingModel == null || embeddingModel.trim().isEmpty()) {
            throw new IllegalStateException("RAG Embedding modelId 不能为空");
        }
        float[] queryVector = embeddingGateway.embed(query.getText().trim());
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalStateException("查询 Embedding 返回了空向量");
        }
        int declaredDimensions = embeddingGateway.dimensions();
        if (declaredDimensions > 0 && declaredDimensions != queryVector.length) {
            throw new IllegalStateException("查询向量维度与 RAG Embedding 配置不一致");
        }

        RagVectorQuery vectorQuery = new RagVectorQuery();
        vectorQuery.setKnowledgeBaseIds(copyIds(query.getKnowledgeBaseIds()));
        vectorQuery.setVector(queryVector);
        vectorQuery.setEmbeddingModel(embeddingModel);
        vectorQuery.setDimensions(queryVector.length);
        vectorQuery.setTopK(Math.max(topK, topK * 3));
        vectorQuery.setMinScore(minScore);
        vectorQuery.setFilters(query.getFilters() == null ? new HashMap<>() : new HashMap<>(query.getFilters()));

        List<RagSearchResult> active = activeResults(indexStore.search(vectorQuery));
        List<RagSearchResult> ranked = rerank(query.getText(), active, topK);
        return new ArrayList<>(ranked.subList(0, Math.min(topK, ranked.size())));
    }

    private List<RagSearchResult> activeResults(List<RagSearchResult> candidates) {
        List<RagSearchResult> result = new ArrayList<>();
        Map<String, RagDocument> documents = new HashMap<>();
        Set<String> seenChunks = new HashSet<>();
        if (candidates == null) {
            return result;
        }
        for (RagSearchResult candidate : candidates) {
            String key = candidate.getKnowledgeBaseId() + ":" + candidate.getDocumentId();
            RagDocument document = documents.computeIfAbsent(key,
                    ignored -> documentStore.find(candidate.getKnowledgeBaseId(), candidate.getDocumentId()));
            if (document == null || document.getStatus() != RagDocument.Status.READY
                    || document.getVersion() != candidate.getDocumentVersion()) {
                continue;
            }
            String chunkKey = candidate.getKnowledgeBaseId() + ":"
                    + candidate.getDocumentId() + ":" + candidate.getChunkId();
            if (seenChunks.add(chunkKey)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private List<RagSearchResult> rerank(String query,
                                         List<RagSearchResult> candidates,
                                         int topK) {
        if (reranker == null || candidates.isEmpty()) {
            return candidates;
        }
        try {
            List<RagSearchResult> reranked = reranker.rerank(query, candidates, topK);
            return reranked == null ? candidates : reranked;
        } catch (RuntimeException e) {
            log.warn("RAG 重排失败，保留向量召回顺序: {}", e.getMessage());
            return candidates;
        }
    }

    private void validate(RagQuery query) {
        if (query == null || query.getText() == null || query.getText().trim().isEmpty()) {
            throw new IllegalArgumentException("RAG 查询文本不能为空");
        }
        List<String> ids = query.getKnowledgeBaseIds();
        if (config.isRequireKnowledgeBaseId() && (ids == null || ids.isEmpty())) {
            throw new IllegalArgumentException("必须指定 knowledgeBaseIds");
        }
        if (ids != null) {
            for (String id : ids) {
                RagFileSupport.requireId("knowledgeBaseId", id);
            }
        }
        if (Double.isNaN(query.getMinScore())) {
            throw new IllegalArgumentException("RAG minScore 不能为 NaN");
        }
        if (query.getFilters() != null) {
            for (Map.Entry<String, String> filter : query.getFilters().entrySet()) {
                if (filter.getKey() == null || filter.getKey().trim().isEmpty()
                        || filter.getValue() == null) {
                    throw new IllegalArgumentException("RAG metadata filter 的键和值不能为空");
                }
            }
        }
    }

    private List<String> copyIds(List<String> ids) {
        return ids == null ? new ArrayList<>() : new ArrayList<>(ids);
    }
}
