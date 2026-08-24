package com.mwb.ai.claw.example.web.rag;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.ObjectProvider;

import com.mwb.ai.claw.domain.rag.model.RagDocument;
import com.mwb.ai.claw.domain.rag.model.RagIngestionCommand;
import com.mwb.ai.claw.domain.rag.store.RagDocumentStore;
import com.mwb.ai.claw.domain.rag.write.RagIngestionService;

/**
 * 示例知识库种子数据（仅 {@code agent.rag.enabled=true} 时装配）。
 *
 * <p>启动时自动创建两个知识库并摄入 Markdown / PDF / Word 三种格式示例文档，
 * 用于演示 T4 RAG 生产化能力：多格式解析（PDFBox / POI）、PGVector 向量库写入、
 * 容量配额与幂等（同名文档已存在则跳过）。
 *
 * <p>依赖 Embedding 接口可用；不可用时打印警告但不阻断启动，可配置
 * {@code RAG_EMBEDDING_*} 后在前端「知识库」页手动摄入。
 */
public class ExampleRagSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExampleRagSeedInitializer.class);

    private final RagIngestionService ingestionService;
    private final RagDocumentStore documentStore;

    public ExampleRagSeedInitializer(RagIngestionService ingestionService, RagDocumentStore documentStore) {
        this.ingestionService = ingestionService;
        this.documentStore = documentStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        ingest("admin-product-docs", "产品手册.md", "text/markdown", productManual(), null);
        ingest("admin-product-docs", "快速上手指南.pdf", "application/pdf", null, quickStartPdf());
        ingest("admin-operations-manual", "运营规范.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", null, operationsDocx());
    }

    private void ingest(String knowledgeBaseId, String name, String contentType,
                        String content, byte[] contentBytes) {
        try {
            List<RagDocument> existing = documentStore.list(knowledgeBaseId);
            for (RagDocument document : existing) {
                if (name.equals(document.getName())) {
                    log.info("[example-web] 知识库 {} 已存在文档 {}，跳过种子摄入", knowledgeBaseId, name);
                    return;
                }
            }
            RagIngestionCommand command = new RagIngestionCommand();
            command.setKnowledgeBaseId(knowledgeBaseId);
            command.setName(name);
            command.setContentType(contentType);
            command.setContent(content);
            command.setContentBytes(contentBytes);
            ingestionService.ingest(command);
            log.info("[example-web] 种子文档摄入成功: {} / {}", knowledgeBaseId, name);
        } catch (Exception e) {
            log.warn("[example-web] 种子文档摄入失败（{} / {}，请确认 RAG_EMBEDDING_* 已配置）: {}",
                    knowledgeBaseId, name, e.getMessage());
        }
    }

    private String productManual() {
        return "# 智能客服助手 产品手册\n\n"
                + "## 一、产品概述\n"
                + "智能客服助手是一款面向电商与零售场景的 AI 客服解决方案，支持 7×24 小时自动应答、"
                + "多轮对话、工单流转与人工接管，可显著降低客服人力成本。\n\n"
                + "## 二、核心能力\n"
                + "1. 自动应答：基于检索增强生成，准确回答商品、物流、售后等高频问题；\n"
                + "2. 多轮对话：结合会话记忆，保持上下文连贯；\n"
                + "3. 工单流转：复杂问题自动创建工单并路由到对应部门；\n"
                + "4. 人工接管：支持一键转人工，无缝衔接。\n\n"
                + "## 三、部署方式\n"
                + "支持公有云 SaaS 与私有化部署两种模式。私有化部署基于容器化架构，"
                + "可对接企业既有统一身份认证与工单系统。\n\n"
                + "## 四、数据安全\n"
                + "对话数据加密存储，支持租户级数据隔离，满足等保与 GDPR 合规要求。\n";
    }

    private byte[] quickStartPdf() {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 16);
                cs.newLineAtOffset(80, 730);
                cs.showText("Smart Customer Service - Quick Start");
                cs.setFont(PDType1Font.HELVETICA, 11);
                for (String line : new String[] {
                        "Step 1: Connect the API, configure the chat endpoint and auth key.",
                        "Step 2: Upload enterprise knowledge base documents (Markdown / PDF / Word).",
                        "Step 3: Publish the bot, serve users via chat page or web widget.",
                        "Step 4: Track sessions, satisfaction and human-handoff rate in console."
                }) {
                    cs.newLineAtOffset(0, -24);
                    cs.showText(line);
                }
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成示例 PDF 失败", e);
        }
    }

    private byte[] operationsDocx() {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph title = document.createParagraph();
            XWPFRun titleRun = title.createRun();
            titleRun.setBold(true);
            titleRun.setText("客服运营规范");

            for (String line : new String[] {
                    "一、响应时效：普通咨询 30 秒内响应，投诉工单 5 分钟内响应。",
                    "二、服务用语：统一使用标准话术模板，避免情绪化表达。",
                    "三、知识库维护：每周更新高频问题与活动规则，确保检索准确率不低于 95%。",
                    "四、质量抽检：每月按 10% 比例抽检会话，评分计入团队 KPI。",
                    "五、数据合规：禁止在对话中透露客户隐私信息，违规操作按红线处理。"
            }) {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.createRun().setText(line);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成示例 Word 失败", e);
        }
    }
}
