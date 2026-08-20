package com.mwb.ai.claw.infrastructure.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import com.mwb.ai.claw.domain.core.ErrorCategory;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

/**
 * LLM 韧性集成测试（C2，真实 HTTP + 假 LLM Server）：
 * 429 指数退避重试、5xx 重试耗尽降级 fallback、读超时转瞬时错误。
 * 使用 JDK 内置 HttpServer，不依赖外部服务。
 */
public class LlmGatewayIntegrationTest {

    private FakeLlmServer primary;
    private FakeLlmServer fallback;
    private AgentProperties.LlmResilienceConfig config;

    @Before
    public void setUp() throws IOException {
        primary = new FakeLlmServer();
        fallback = new FakeLlmServer();
        primary.start();
        fallback.start();

        config = new AgentProperties.LlmResilienceConfig();
        AgentProperties.LlmRetryConfig retry = new AgentProperties.LlmRetryConfig();
        retry.setMaxAttempts(3);
        retry.setInitialBackoffMs(1);
        retry.setMaxBackoffMs(5);
        config.setRetry(retry);
    }

    @After
    public void tearDown() {
        primary.stop();
        fallback.stop();
    }

    private LlmGateway gateway(int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(500);
        factory.setReadTimeout(readTimeoutMs);
        LlmGatewayImpl impl = new LlmGatewayImpl(new RestTemplate(factory), null, 500, readTimeoutMs);
        return new ResilientLlmGateway(impl, config, null);
    }

    private ModelConfig model(String name, String baseUrl) {
        ModelConfig mc = new ModelConfig();
        mc.setModel(name);
        mc.setBaseUrl(baseUrl);
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

    /** 429 两次后第三次 200 → 重试最终成功，共 3 次请求 */
    @Test
    public void testRetryOn429_thenSuccess() throws IOException {
        primary.handler = new SequentialHandler(new int[]{429, 429}, c -> okBody("retried-ok"));
        LlmResponse result = gateway(3000).chat(request(), model("primary", primary.baseUrl()));
        assertEquals("stop", result.getFinishReason());
        assertTrue(result.getContent().contains("retried-ok"));
        assertEquals("429 应重试 2 次", 3, primary.requestCount.get());
    }

    /** 主模型恒 5xx，fallback 正常 → 重试耗尽后降级成功 */
    @Test
    public void testFallback_onPersistent5xx() throws IOException {
        primary.handler = (count) -> errorBody(500, "server boom");
        fallback.handler = c -> okBody("fallback-ok");
        config.setFallbackModel("fallback-model");
        config.setFallbackBaseUrl(fallback.baseUrl());
        LlmResponse result = gateway(3000).chat(request(), model("primary", primary.baseUrl()));
        assertTrue("fallback 结果应为成功，实际=" + result.getFinishReason() + ":" + result.getContent(),
                result.getContent().contains("fallback-ok"));
        assertEquals("主模型 4 次（初次+3 重试）", 4, primary.requestCount.get());
        assertEquals("fallback 1 次", 1, fallback.requestCount.get());
    }

    /** 服务端慢响应超过读超时 → 网络错误 → 重试 → 重试耗尽返回瞬时错误 */
    @Test
    public void testReadTimeout_returnsTransientError() throws IOException {
        config.getRetry().setMaxAttempts(1); // 初次 + 1 次重试，控制总耗时
        primary.handler = (count) -> {
            sleep(1500);
            return okBody("slow-ok");
        };
        LlmResponse result = gateway(300).chat(request(), model("primary", primary.baseUrl()));
        assertEquals("error", result.getFinishReason());
        assertEquals(ErrorCategory.TRANSIENT, result.getErrorCategory());
        assertTrue(result.getContent().startsWith("LLM 调用失败"));
    }

    // ---------- 工具 ----------

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String okBody(String content) {
        return "{\"id\":\"cmpl-test\",\"object\":\"chat.completion\",\"model\":\"test\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\""
                + content + "\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3,\"total_tokens\":8}}";
    }

    private static String errorBody(int code, String msg) {
        return "{\"error\":{\"message\":\"" + msg + "\",\"type\":\"server_error\",\"code\":\""
                + code + "\"}}";
    }

    /** 按请求序号返回响应：前 failTimes 次错误，之后成功 */
    private static class SequentialHandler implements IntFunction<String> {
        private final int[] failStatuses;
        private final IntFunction<String> successBody;

        SequentialHandler(int[] failStatuses, IntFunction<String> successBody) {
            this.failStatuses = failStatuses;
            this.successBody = successBody;
        }

        @Override
        public String apply(int count) {
            int idx = count - 1;
            if (idx >= 0 && idx < failStatuses.length) {
                return errorBody(failStatuses[idx], "rate limited " + count);
            }
            return successBody.apply(count);
        }
    }

    /** 轻量假 LLM Server：context /v1/chat/completions，按请求计数返回 */
    private static class FakeLlmServer {
        private com.sun.net.httpserver.HttpServer server;
        final AtomicInteger requestCount = new AtomicInteger();
        /** 入参：当前请求序号（1 起），返回响应体字符串 */
        IntFunction<String> handler = (count) -> okBody("default-ok");

        void start() throws IOException {
            server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                byte[] reqBody = readAll(exchange.getRequestBody());
                if (reqBody.length == 0) {
                    exchange.sendResponseHeaders(400, -1);
                    exchange.close();
                    return;
                }
                int count = requestCount.incrementAndGet();
                String body = handler.apply(count);
                int status = body.contains("\"server_error\"") ? 500
                        : body.contains("\"rate limited\"") || body.contains("\"error\"") ? 429 : 200;
                byte[] resp = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
                exchange.close();
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        /** JDK8 兼容：读取输入流全部字节（readAllBytes 为 JDK9+ API） */
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
