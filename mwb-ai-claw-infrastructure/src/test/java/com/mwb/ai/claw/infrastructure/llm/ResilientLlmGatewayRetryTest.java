package com.mwb.ai.claw.infrastructure.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.Before;
import org.junit.Test;

import com.mwb.ai.claw.domain.core.ErrorCategory;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

/**
 * ResilientLlmGateway 韧性行为测试（C2）：
 * 瞬时错误指数退避重试、重试耗尽降级 fallback、非瞬时异常不重试、
 * token 预算超限返回 BUDGET 错误、maxAttempts=0 关闭重试。
 */
public class ResilientLlmGatewayRetryTest {

    private AgentProperties.LlmResilienceConfig config;
    private RecordingDelegate delegate;

    @Before
    public void setUp() {
        config = new AgentProperties.LlmResilienceConfig();
        AgentProperties.LlmRetryConfig retry = new AgentProperties.LlmRetryConfig();
        retry.setMaxAttempts(2);
        retry.setInitialBackoffMs(1);
        retry.setMaxBackoffMs(5);
        config.setRetry(retry);
        delegate = new RecordingDelegate();
    }

    private ResilientLlmGateway newGateway() {
        return new ResilientLlmGateway(delegate, config, null);
    }

    private ModelConfig model(String name) {
        ModelConfig mc = new ModelConfig();
        mc.setModel(name);
        mc.setBaseUrl("http://127.0.0.1:1/v1");
        return mc;
    }

    private LlmRequest request() {
        LlmRequest req = new LlmRequest();
        req.setModel("primary");
        req.setMessages(new ArrayList<>());
        return req;
    }

    @Test
    public void testRetryOnTransientError_thenSuccess() {
        delegate.failFirst = 1; // 前 1 次抛瞬时错误
        LlmResponse result = newGateway().chat(request(), model("primary"));
        assertTrue(result.getContent().startsWith("ok"));
        assertEquals("瞬时错误应重试", 2, delegate.callCount("primary"));
    }

    @Test
    public void testRetryExhausted_noFallback_returnsTransientError() {
        delegate.failAlways = true;
        LlmResponse result = newGateway().chat(request(), model("primary"));
        assertEquals("error", result.getFinishReason());
        assertEquals(ErrorCategory.TRANSIENT, result.getErrorCategory());
        assertTrue(result.getContent().startsWith("LLM 调用失败"));
        assertEquals("maxAttempts=2 → 初次 + 2 次重试", 3, delegate.callCount("primary"));
    }

    @Test
    public void testMaxAttemptsZero_disablesRetry() {
        config.getRetry().setMaxAttempts(0);
        delegate.failAlways = true;
        LlmResponse result = newGateway().chat(request(), model("primary"));
        assertEquals("error", result.getFinishReason());
        assertEquals("maxAttempts=0 不重试", 1, delegate.callCount("primary"));
    }

    @Test
    public void testFallback_usedAfterRetryExhausted() {
        config.setFallbackModel("fallback-model");
        delegate.failAlways = true;
        LlmResponse result = newGateway().chat(request(), model("primary"));
        assertTrue("fallback 成功应作为最终结果", result.getContent().startsWith("ok"));
        assertEquals("primary 重试 3 次", 3, delegate.callCount("primary"));
        assertEquals("fallback 调用 1 次", 1, delegate.callCount("fallback-model"));
    }

    @Test
    public void testFallbackAlsoFails_returnsError() {
        config.setFallbackModel("fallback-model");
        delegate.failAlways = true;   // 主模型持续瞬时失败，耗尽重试
        delegate.failFallback = true; // 备用模型也失败
        LlmResponse result = newGateway().chat(request(), model("primary"));
        assertEquals("error", result.getFinishReason());
        assertEquals(ErrorCategory.TRANSIENT, result.getErrorCategory());
    }

    @Test
    public void testRateLimit_faultInjection_retriesThenSucceeds() {
        delegate.failFirst = 1;
        delegate.failureMsg = "HTTP 429: rate limited";
        LlmResponse result = newGateway().chat(request(), model("primary"));
        assertTrue(result.getContent().startsWith("ok"));
        assertEquals("限流瞬时错误应重试后成功", 2, delegate.callCount("primary"));
    }

    @Test
    public void testHttp5xx_faultInjection_retriesThenSucceeds() {
        delegate.failFirst = 2;
        delegate.failureMsg = "HTTP 502: upstream error";
        LlmResponse result = newGateway().chat(request(), model("primary"));
        assertTrue(result.getContent().startsWith("ok"));
        assertEquals("5xx 应重试至成功", 3, delegate.callCount("primary"));
    }

    @Test
    public void testTimeout_faultInjection_fallsBackAfterRetryExhausted() {
        config.setFallbackModel("fallback-model");
        delegate.failAlways = true;
        delegate.failureMsg = "connect timed out";
        LlmResponse result = newGateway().chat(request(), model("primary"));
        assertTrue("主模型超时耗尽后应降级到备用模型", result.getContent().startsWith("ok"));
        assertEquals("primary 重试耗尽 (1 初次 + 2 重试)", 3, delegate.callCount("primary"));
        assertEquals("fallback 调用 1 次", 1, delegate.callCount("fallback-model"));
    }

    @Test
    public void testTimeout_faultInjection_noFallback_returnsTransientError() {
        delegate.failAlways = true;
        delegate.failureMsg = "read timed out";
        LlmResponse result = newGateway().chat(request(), model("primary"));
        assertEquals("error", result.getFinishReason());
        assertEquals(ErrorCategory.TRANSIENT, result.getErrorCategory());
        assertEquals("超时无备用模型应重试耗尽后报瞬时错误", 3, delegate.callCount("primary"));
    }

    @Test
    public void testNonRetryableException_propagatedWithoutRetry() {
        delegate.boom = new IllegalStateException("业务层错误");
        try {
            newGateway().chat(request(), model("primary"));
            fail("非瞬时异常应直接传播");
        } catch (IllegalStateException expected) {
            // expected
        }
        assertEquals("非瞬时异常不重试", 1, delegate.callCount("primary"));
    }

    @Test
    public void testBudgetExceeded_returnsBudgetError() {
        RunTokenBudget.bind(10);
        try {
            LlmResponse ok = new LlmResponse();
            ok.setContent("ok");
            ok.setPromptTokens(100);
            delegate.next = ok;
            LlmResponse result = newGateway().chat(request(), model("primary"));
            assertEquals("error", result.getFinishReason());
            assertEquals(ErrorCategory.BUDGET, result.getErrorCategory());
            assertTrue(result.getContent().contains("token 预算上限"));
        } finally {
            RunTokenBudget.unbind();
        }
    }

    @Test
    public void testBudgetOk_passesThrough() {
        RunTokenBudget.bind(1000);
        try {
            LlmResponse ok = new LlmResponse();
            ok.setContent("ok");
            ok.setPromptTokens(30);
            ok.setCompletionTokens(10);
            delegate.next = ok;
            LlmResponse result = newGateway().chat(request(), model("primary"));
            assertSame(ok, result);
        } finally {
            RunTokenBudget.unbind();
        }
    }

    /** 记录各 model 调用次数，可按需抛瞬时错误 / 成功 / 业务异常 */
    private static class RecordingDelegate implements LlmGateway {
        final Map<String, Integer> calls = new ConcurrentHashMap<>();
        int failFirst;
        boolean failAlways;
        boolean failFallback;
        RuntimeException boom;
        LlmResponse next;
        String failureMsg = "HTTP 429: rate limited";

        int callCount(String model) {
            return calls.getOrDefault(model, 0);
        }

        private LlmResponse doCall(String model) {
            calls.merge(model, 1, Integer::sum);
            if (boom != null) {
                throw boom;
            }
            boolean isPrimary = model.equals("primary");
            boolean isFallback = model.equals("fallback-model");
            if ((isPrimary && (failAlways || failFirst-- > 0)) || (isFallback && failFallback)) {
                throw new RetryableLlmException(failureMsg);
            }
            if (next != null) {
                return next;
            }
            LlmResponse r = new LlmResponse();
            r.setContent("ok from " + model);
            r.setFinishReason("stop");
            r.setPromptTokens(1);
            r.setCompletionTokens(1);
            return r;
        }

        @Override
        public LlmResponse chat(LlmRequest request, ModelConfig modelConfig) {
            return doCall(modelConfig.getModel());
        }

        @Override
        public LlmResponse streamChat(LlmRequest request, ModelConfig modelConfig,
                                      com.mwb.ai.claw.domain.llm.LlmStreamCallback callback) {
            return doCall(modelConfig.getModel());
        }
    }
}
