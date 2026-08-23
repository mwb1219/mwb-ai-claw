package com.mwb.ai.claw.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import javax.annotation.Resource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mwb.ai.claw.domain.rag.model.RagDocument;
import com.mwb.ai.claw.domain.rag.model.RagIngestionCommand;
import com.mwb.ai.claw.domain.rag.model.RagIngestionResult;
import com.mwb.ai.claw.domain.rag.model.RagQuery;
import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.domain.rag.retrieve.RagRetrievalService;
import com.mwb.ai.claw.domain.rag.store.RagDocumentStore;
import com.mwb.ai.claw.domain.rag.write.RagIngestionService;
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

    /**
     * 文件上传摄入：读取文本/Markdown 文件内容并走同一写入链路（解析 → 切分 → 向量化 → 索引）。
     * 文件大小上限由 {@code spring.servlet.multipart.max-file-size} 控制（默认不限制）。
     */
    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents/upload")
    public SingleResponse<RagIngestionResult> upload(
            @PathVariable String knowledgeBaseId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String documentId,
            @RequestParam(required = false) String name) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        RagIngestionCommand command = new RagIngestionCommand();
        command.setKnowledgeBaseId(knowledgeBaseId);
        command.setDocumentId(documentId);
        command.setName(name != null && !name.trim().isEmpty() ? name.trim() : file.getOriginalFilename());
        command.setContentType(resolveContentType(file.getOriginalFilename()));
        command.setContent(new String(file.getBytes(), StandardCharsets.UTF_8));
        return SingleResponse.of(ingestionService.ingest(command));
    }

    /** 依据文件扩展名推断内容类型：.md/.markdown → text/markdown，其余按纯文本处理。 */
    private String resolveContentType(String filename) {
        if (filename == null) {
            return "text/plain";
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "text/markdown";
        }
        return "text/plain";
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
