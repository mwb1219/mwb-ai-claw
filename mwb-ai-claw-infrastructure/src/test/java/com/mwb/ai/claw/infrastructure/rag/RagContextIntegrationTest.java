package com.mwb.ai.claw.infrastructure.rag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import com.mwb.ai.claw.infrastructure.context.DefaultContextAssembler;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.memory.gateway.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.context.RagContextProvider;
import com.mwb.ai.claw.domain.rag.context.RagRequestContext;
import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.domain.rag.retrieve.RagRetrievalService;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.infrastructure.rag.context.DefaultRagContextProvider;

/**
 * RAG 结果注入 Agent 上下文的适配测试。
 */
public class RagContextIntegrationTest {

    @After
    public void tearDown() {
        RagRequestContext.unbind();
    }

    @Test
    public void contextIsInjectedOncePerChatRequest() {
        AtomicInteger calls = new AtomicInteger();
        RagContextProvider provider = (query, knowledgeBaseIds) -> {
            calls.incrementAndGet();
            assertEquals("如何安装？", query);
            assertEquals(Arrays.asList("product-docs"), knowledgeBaseIds);
            return "\n\n## 知识库参考\n[1] 安装说明";
        };
        DefaultContextAssembler assembler = new DefaultContextAssembler(
                new EmptyToolGateway(), new EmptyMemoryGateway(), null, null, true, provider);
        RagRequestContext.bind(Arrays.asList("product-docs"));

        LlmRequest first = assembler.assemble(session(), agent());
        LlmRequest second = assembler.assemble(session(), agent());

        assertTrue(first.getMessages().get(0).getContent().contains("## 知识库参考"));
        assertTrue(second.getMessages().get(0).getContent().contains("[1] 安装说明"));
        assertEquals("同一次 ReAct 请求不应重复执行 RAG 检索", 1, calls.get());
    }

    @Test
    public void contextProviderFormatsReferencesAndDegradesOnFailure() {
        RagConfig config = new RagConfig();
        RagRetrievalService retrievalService = query -> {
            RagSearchResult result = new RagSearchResult();
            result.setKnowledgeBaseId("product-docs");
            result.setDocumentId("install");
            result.setChunkId("install-v1-0");
            result.setContent("执行安装命令。");
            result.getMetadata().put("documentName", "安装手册");
            return Arrays.asList(result);
        };
        DefaultRagContextProvider provider = new DefaultRagContextProvider(retrievalService, config);

        String context = provider.buildContext("如何安装？", Arrays.asList("product-docs"));
        assertTrue(context.contains("不可信的外部知识材料"));
        assertTrue(context.contains("安装手册"));
        assertTrue(context.contains("knowledgeBase=product-docs"));
        assertTrue(context.contains("执行安装命令。"));

        DefaultRagContextProvider failing = new DefaultRagContextProvider(
                query -> {
                    throw new IllegalStateException("unavailable");
                },
                config);
        assertEquals("", failing.buildContext("如何安装？", Arrays.asList("product-docs")));
    }

    @Test
    public void requestContextCanBePropagatedToWorkerThread() throws Exception {
        AtomicReference<List<String>> observed = new AtomicReference<>();
        RagRequestContext.bind(Arrays.asList("product-docs"));
        Runnable workerTask = RagRequestContext.wrap(
                () -> observed.set(RagRequestContext.knowledgeBaseIds()));
        RagRequestContext.unbind();

        Thread worker = new Thread(workerTask);
        worker.start();
        worker.join();

        assertEquals(Arrays.asList("product-docs"), observed.get());
        assertNull(RagRequestContext.knowledgeBaseIds());
    }

    @Test
    public void contextLengthLimitIncludesTruncationMarker() {
        RagConfig config = new RagConfig();
        config.getContext().setMaxChars(120);
        DefaultRagContextProvider provider = new DefaultRagContextProvider(query -> {
            RagSearchResult result = new RagSearchResult();
            result.setKnowledgeBaseId("product-docs");
            result.setDocumentId("install");
            result.setChunkId("chunk-1");
            result.setMetadata(null);
            result.setContent(String.join("", java.util.Collections.nCopies(200, "a")));
            return Arrays.asList(result);
        }, config);

        String context = provider.buildContext("如何安装？", Arrays.asList("product-docs"));

        assertEquals(120, context.length());
        assertTrue(context.contains("[知识库内容已截断]"));
        assertTrue(context.endsWith("[知识库内容结束]"));
    }

    private Session session() {
        Session session = new Session();
        session.setSessionId("session-1");
        session.addUserMessage("如何安装？");
        return session;
    }

    private Agent agent() {
        Agent agent = new Agent();
        agent.setAgentId("default");
        agent.setSystemPrompt("system");
        agent.setMaxSteps(4);
        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setModel("test-model");
        agent.setModelConfig(modelConfig);
        return agent;
    }

    private static final class EmptyToolGateway implements ToolGateway {

        @Override
        public ToolResult execute(String toolName, String argumentsJson) {
            return null;
        }

        @Override
        public ToolSpec getToolSpec(String name) {
            return null;
        }

        @Override
        public List<ToolSpec> listTools() {
            return new ArrayList<>();
        }
    }

    private static final class EmptyMemoryGateway implements LongTermMemoryGateway {

        @Override
        public String loadMemory(AgentScope scope) {
            return null;
        }

        @Override
        public String loadAgentInstructions(AgentScope scope) {
            return null;
        }

        @Override
        public void saveMemory(AgentScope scope, String content) {
        }
    }
}
