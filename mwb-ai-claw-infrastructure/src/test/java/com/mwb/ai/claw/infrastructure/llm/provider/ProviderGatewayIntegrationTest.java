package com.mwb.ai.claw.infrastructure.llm.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.ContentPart;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.infrastructure.llm.LlmGatewayImpl;

/**
 * Provider 协议网关集成测试（D1/D2）：真实 HTTP + 假 Provider Server。
 * 覆盖：路由分派（openai/anthropic/gemini/ollama）、response_format 翻译
 * （json_object / json_schema）、多模态 parts 翻译（image_base64）。
 */
public class ProviderGatewayIntegrationTest {

    private FakeProviderServer openAi;
    private FakeProviderServer anthropic;
    private FakeProviderServer gemini;

    private LlmGateway routing;

    @Before
    public void setUp() throws IOException {
        openAi = new FakeProviderServer("/v1/chat/completions", "/v1");
        anthropic = new FakeProviderServer("/v1/messages", "/v1");
        gemini = new FakeProviderServer("/v1beta/models/test-model:generateContent", "/v1beta");
        openAi.start();
        anthropic.start();
        gemini.start();

        RestTemplate restTemplate = restTemplate();
        LlmGatewayImpl openAiGateway = new LlmGatewayImpl(restTemplate);
        AnthropicLlmGateway anthropicGateway = new AnthropicLlmGateway(restTemplate, null, 500, 3000);
        GeminiLlmGateway geminiGateway = new GeminiLlmGateway(restTemplate, null, 500, 3000);
        routing = new ProviderRoutingGateway(openAiGateway, anthropicGateway, geminiGateway);
    }

    @After
    public void tearDown() {
        openAi.stop();
        anthropic.stop();
        gemini.stop();
    }

    private static RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(500);
        factory.setReadTimeout(3000);
        return new RestTemplate(factory);
    }

    private ModelConfig model(String provider, FakeProviderServer server) {
        ModelConfig mc = new ModelConfig();
        mc.setProvider(provider);
        mc.setModel("test-model");
        mc.setBaseUrl(server.baseUrl());
        mc.setApiKey("test-key");
        return mc;
    }

    private LlmRequest request() {
        LlmRequest req = new LlmRequest();
        LlmMessage msg = new LlmMessage();
        msg.setRole("user");
        msg.setContent("hello");
        req.setMessages(new ArrayList<>());
        req.getMessages().add(msg);
        return req;
    }

    // ---------- 路由分派 ----------

    @Test
    public void testOpenAiDefaultRouting() {
        LlmResponse resp = routing.chat(request(), model("openai", openAi));
        assertEquals("stop", resp.getFinishReason());
        assertEquals(1, openAi.requestCount.get());
        assertEquals(0, anthropic.requestCount.get());
        assertEquals(0, gemini.requestCount.get());
    }

    @Test
    public void testOllamaRoutesToOpenAiProtocol() {
        routing.chat(request(), model("ollama", openAi));
        assertEquals("Ollama 应复用 OpenAI 兼容端点", 1, openAi.requestCount.get());
    }

    @Test
    public void testAnthropicRouting() {
        LlmResponse resp = routing.chat(request(), model("anthropic", anthropic));
        assertEquals("stop", resp.getFinishReason());
        assertTrue(resp.getContent().contains("ok"));
        assertEquals(1, anthropic.requestCount.get());
        assertEquals(0, openAi.requestCount.get());
    }

    @Test
    public void testGeminiRouting() {
        LlmResponse resp = routing.chat(request(), model("gemini", gemini));
        assertEquals("stop", resp.getFinishReason());
        assertTrue(resp.getContent().contains("ok"));
        assertEquals(1, gemini.requestCount.get());
        assertEquals(0, openAi.requestCount.get());
    }

    // ---------- OpenAI 协议：response_format 翻译（D2） ----------

    @Test
    public void testOpenAiJsonObject_addsHintWhenNoJsonWord() {
        LlmRequest req = request();
        req.setResponseFormat("json_object");
        routing.chat(req, model("openai", openAi));
        String body = openAi.lastRequestBody();
        assertTrue("请求体应含 response_format", body.contains("\"response_format\":{\"type\":\"json_object\"}"));
        assertTrue("json_object 需追加 JSON 提示", body.contains("请以 JSON 对象格式输出"));
    }

    @Test
    public void testOpenAiJsonSchema_wrapsStrictSchema() {
        LlmRequest req = request();
        req.setResponseFormat("json_schema");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<String, Object>());
        req.setJsonSchema(schema);
        routing.chat(req, model("openai", openAi));
        String body = openAi.lastRequestBody();
        assertTrue("请求体应含 json_schema", body.contains("\"json_schema\""));
        assertTrue("json_schema 需 strict=true", body.contains("\"strict\":true"));
        assertTrue("应携带 schema", body.contains("\"type\":\"object\""));
    }

    // ---------- OpenAI 协议：多模态翻译（D2） ----------

    @Test
    public void testOpenAiMultimodalParts() {
        LlmRequest req = request();
        List<ContentPart> parts = new ArrayList<>();
        parts.add(ContentPart.text("描述这张图"));
        parts.add(ContentPart.imageBase64("image/png", "QUJDRA=="));
        req.getMessages().get(0).setParts(parts);
        routing.chat(req, model("openai", openAi));
        String body = openAi.lastRequestBody();
        assertTrue("content 应数组化", body.contains("\"content\":[{"));
        assertTrue("应含 image_url data URL", body.contains("data:image/png;base64,QUJDRA=="));
    }

    // ---------- Anthropic 协议：JSON 约束 + 图片 blocks（D2） ----------

    @Test
    public void testAnthropicJsonConstraint() {
        LlmRequest req = request();
        req.setResponseFormat("json_object");
        routing.chat(req, model("anthropic", anthropic));
        String body = anthropic.lastRequestBody();
        assertTrue("system 应追加 JSON 约束段", body.contains("仅输出合法 JSON"));
        assertTrue("x-api-key 认证头", anthropic.lastRequestHeader("x-api-key").contains("test-key"));
    }

    @Test
    public void testAnthropicImageBlocks() {
        LlmRequest req = request();
        List<ContentPart> parts = new ArrayList<>();
        parts.add(ContentPart.text("看这张图"));
        parts.add(ContentPart.imageBase64("image/png", "QUJDRA=="));
        req.getMessages().get(0).setParts(parts);
        routing.chat(req, model("anthropic", anthropic));
        String body = anthropic.lastRequestBody();
        assertTrue("应含 image block", body.contains("\"type\":\"image\""));
        assertTrue("应含 base64 source", body.contains("\"type\":\"base64\""));
    }

    // ---------- Gemini 协议：response_schema + inlineData（D2） ----------

    @Test
    public void testGeminiJsonSchema() {
        LlmRequest req = request();
        req.setResponseFormat("json_schema");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        req.setJsonSchema(schema);
        routing.chat(req, model("gemini", gemini));
        String body = gemini.lastRequestBody();
        assertTrue("应含 responseMimeType", body.contains("\"responseMimeType\":\"application/json\""));
        assertTrue("应含 responseSchema", body.contains("\"responseSchema\""));
    }

    @Test
    public void testGeminiInlineData() {
        LlmRequest req = request();
        List<ContentPart> parts = new ArrayList<>();
        parts.add(ContentPart.text("看这张图"));
        parts.add(ContentPart.imageBase64("image/png", "QUJDRA=="));
        req.getMessages().get(0).setParts(parts);
        routing.chat(req, model("gemini", gemini));
        String body = gemini.lastRequestBody();
        assertTrue("应含 inlineData", body.contains("\"inlineData\""));
        assertTrue("应含 base64 data", body.contains("\"data\":\"QUJDRA==\""));
    }

    // ---------- 假 Provider Server ----------

    /** 轻量假 Provider Server：按 path 区分端点，捕获最近一次请求体与请求头 */
    private static class FakeProviderServer {
        private final String path;
        private final String basePrefix;
        private com.sun.net.httpserver.HttpServer server;
        final AtomicInteger requestCount = new AtomicInteger();
        private volatile String lastBody;
        private final Map<String, String> lastHeaders = new LinkedHashMap<>();
        IntFunction<String> handler;

        FakeProviderServer(String path, String basePrefix) {
            this.path = path;
            this.basePrefix = basePrefix;
        }

        void start() throws IOException {
            handler = (count) -> responseBody(path);
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(path, exchange -> {
                byte[] reqBody = readAll(exchange.getRequestBody());
                requestCount.incrementAndGet();
                lastBody = new String(reqBody, StandardCharsets.UTF_8);
                lastHeaders.clear();
                for (Map.Entry<String, List<String>> e : exchange.getRequestHeaders().entrySet()) {
                    lastHeaders.put(e.getKey().toLowerCase(), e.getValue().isEmpty() ? "" : e.getValue().get(0));
                }
                String body = handler.apply(requestCount.get());
                byte[] resp = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
                exchange.close();
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + basePrefix;
        }

        String lastRequestBody() {
            return lastBody;
        }

        String lastRequestHeader(String name) {
            return lastHeaders.getOrDefault(name.toLowerCase(), "");
        }

        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        /** 按端点返回对应协议的合法响应 */
        private static String responseBody(String path) {
            if (path.contains("generateContent")) {
                return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":3}}";
            }
            if (path.contains("/messages")) {
                return "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\","
                        + "\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}";
            }
            return "{\"id\":\"cmpl-test\",\"object\":\"chat.completion\",\"model\":\"test\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                    + "\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}";
        }

        /** JDK8 兼容：读取输入流全部字节 */
        private static byte[] readAll(java.io.InputStream in) throws IOException {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
