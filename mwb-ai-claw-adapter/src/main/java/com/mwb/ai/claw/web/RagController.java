package com.mwb.ai.claw.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import javax.annotation.Resource;

import org.springframework.beans.factory.ObjectProvider;
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

import com.mwb.ai.claw.domain.rag.access.RagAccessPolicy;
import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.model.RagDocument;
import com.mwb.ai.claw.domain.rag.model.RagIngestionCommand;
import com.mwb.ai.claw.domain.rag.model.RagIngestionResult;
import com.mwb.ai.claw.domain.rag.model.RagQuery;
import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.domain.rag.retrieve.RagRetrievalService;
import com.mwb.ai.claw.domain.rag.store.RagDocumentStore;
import com.mwb.ai.claw.domain.rag.write.RagIngestionService;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.dto.SingleResponse;

/**
 * RAG 管理与检索接口。知识库为全局资源，默认不读取 AgentScope；
 * {@code agent.rag.access.enabled=true} 时按 {@link RagAccessPolicy} 做 API 层授权。
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

    @Resource
    private RagConfig ragConfig;

    @Resource
    private ObjectProvider<RagAccessPolicy> accessPolicyProvider;

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public SingleResponse<RagIngestionResult> ingest(
            @PathVariable String knowledgeBaseId,
            @RequestBody RagIngestionCommand command) {
        checkAccess(knowledgeBaseId, RagAccessPolicy.Action.WRITE);
        command.setKnowledgeBaseId(knowledgeBaseId);
        return SingleResponse.of(ingestionService.ingest(command));
    }

    /**
     * 文件上传摄入：按扩展名识别文本 / Markdown / PDF / Word(.docx)，走同一写入链路
     * （解析 → 切分 → 向量化 → 索引）。文件大小上限由 {@code spring.servlet.multipart.max-file-size}
     * 控制（默认不限制）。
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
        checkAccess(knowledgeBaseId, RagAccessPolicy.Action.WRITE);
        String contentType = resolveContentType(file.getOriginalFilename());
        RagIngestionCommand command = new RagIngestionCommand();
        command.setKnowledgeBaseId(knowledgeBaseId);
        command.setDocumentId(documentId);
        command.setName(name != null && !name.trim().isEmpty() ? name.trim() : file.getOriginalFilename());
        command.setContentType(contentType);
        byte[] bytes = file.getBytes();
        if (isBinaryContentType(contentType)) {
            command.setContentBytes(bytes);
        } else {
            command.setContent(new String(bytes, StandardCharsets.UTF_8));
        }
        return SingleResponse.of(ingestionService.ingest(command));
    }

    /** 依据文件扩展名推断内容类型：.md/.markdown → text/markdown；.pdf → application/pdf；.docx → Word；其余纯文本。 */
    private String resolveContentType(String filename) {
        if (filename == null) {
            return "text/plain";
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        return "text/plain";
    }

    private boolean isBinaryContentType(String contentType) {
        return contentType.startsWith("application/pdf")
                || contentType.startsWith("application/msword")
                || contentType.startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml")
                || contentType.startsWith("application/octet-stream");
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/reindex")
    public SingleResponse<RagIngestionResult> reindex(
            @PathVariable String knowledgeBaseId,
            @PathVariable String documentId) {
        checkAccess(knowledgeBaseId, RagAccessPolicy.Action.WRITE);
        return SingleResponse.of(ingestionService.reindex(knowledgeBaseId, documentId));
    }

    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}/documents/{documentId}")
    public SingleResponse<Void> delete(
            @PathVariable String knowledgeBaseId,
            @PathVariable String documentId) {
        checkAccess(knowledgeBaseId, RagAccessPolicy.Action.DELETE);
        ingestionService.deleteDocument(knowledgeBaseId, documentId);
        return SingleResponse.buildSuccess();
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public SingleResponse<List<RagDocument>> list(@PathVariable String knowledgeBaseId) {
        checkAccess(knowledgeBaseId, RagAccessPolicy.Action.READ);
        return SingleResponse.of(documentStore.list(knowledgeBaseId));
    }

    @PostMapping("/search")
    public SingleResponse<List<RagSearchResult>> search(@RequestBody RagQuery query) {
        if (query.getKnowledgeBaseIds() != null) {
            for (String knowledgeBaseId : query.getKnowledgeBaseIds()) {
                checkAccess(knowledgeBaseId, RagAccessPolicy.Action.READ);
            }
        }
        return SingleResponse.of(retrievalService.retrieve(query));
    }

    /** 仅当 agent.rag.access.enabled=true 时按注入的访问策略鉴权；关闭时全部放行。 */
    private void checkAccess(String knowledgeBaseId, RagAccessPolicy.Action action) {
        if (!ragConfig.getAccess().isEnabled()) {
            return;
        }
        RagAccessPolicy policy = accessPolicyProvider.getIfAvailable();
        if (policy == null) {
            return;
        }
        String tenantId = AgentScopeContext.get().getTenantId();
        String userId = AgentScopeContext.get().getUserId();
        if (!policy.canAccess(tenantId, userId, knowledgeBaseId, action)) {
            throw new IllegalArgumentException("无权对知识库执行 " + action + " 操作: " + knowledgeBaseId);
        }
    }
}
