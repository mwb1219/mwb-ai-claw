package com.mwb.ai.claw.web;

import java.util.List;

import javax.annotation.Resource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mwb.ai.claw.domain.rag.RagDocument;
import com.mwb.ai.claw.domain.rag.RagDocumentStore;
import com.mwb.ai.claw.domain.rag.RagIngestionCommand;
import com.mwb.ai.claw.domain.rag.RagIngestionResult;
import com.mwb.ai.claw.domain.rag.RagIngestionService;
import com.mwb.ai.claw.domain.rag.RagQuery;
import com.mwb.ai.claw.domain.rag.RagRetrievalService;
import com.mwb.ai.claw.domain.rag.RagSearchResult;
import com.mwb.ai.claw.dto.SingleResponse;

/**
 * RAG 管理与检索接口。知识库为全局资源，不读取 AgentScope。
 */
@RestController
@RequestMapping("/rag")
@Profile("web")
@ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true")
public class RagController {

    @Resource
    private RagIngestionService ingestionService;

    @Resource
    private RagRetrievalService retrievalService;

    @Resource
    private RagDocumentStore documentStore;

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public SingleResponse<RagIngestionResult> ingest(
            @PathVariable String knowledgeBaseId,
            @RequestBody RagIngestionCommand command) {
        command.setKnowledgeBaseId(knowledgeBaseId);
        return SingleResponse.of(ingestionService.ingest(command));
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/reindex")
    public SingleResponse<RagIngestionResult> reindex(
            @PathVariable String knowledgeBaseId,
            @PathVariable String documentId) {
        return SingleResponse.of(ingestionService.reindex(knowledgeBaseId, documentId));
    }

    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}/documents/{documentId}")
    public SingleResponse<Void> delete(
            @PathVariable String knowledgeBaseId,
            @PathVariable String documentId) {
        ingestionService.deleteDocument(knowledgeBaseId, documentId);
        return SingleResponse.buildSuccess();
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public SingleResponse<List<RagDocument>> list(@PathVariable String knowledgeBaseId) {
        return SingleResponse.of(documentStore.list(knowledgeBaseId));
    }

    @PostMapping("/search")
    public SingleResponse<List<RagSearchResult>> search(@RequestBody RagQuery query) {
        return SingleResponse.of(retrievalService.retrieve(query));
    }
}
