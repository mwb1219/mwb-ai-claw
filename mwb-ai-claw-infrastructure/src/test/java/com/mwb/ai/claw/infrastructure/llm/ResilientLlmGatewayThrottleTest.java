package com.mwb.ai.claw.infrastructure.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
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
 * T7：LLM 网关请求级限流与模型级熔断测试。
 * <ul>
 *   <li>LlmRateLimiter：QPS 超限拒绝、并发超限拒绝、release 归还后恢复；</li>
 *   <li>LlmCircuitBreaker：错误率达标触发熔断、熔断期短路、到期半开恢复；</li>
 *   <li>ResilientLlmGateway 集成：限流命中返回错误不调下游、熔断命中短路不调下游。</li>
 * </ul>
 */
public class ResilientLlmGatewayThrottleTest {

    private AgentProperties.LlmResilienceConfig config;
    private RecordingDelegate delegate;

    @Before
    public void setUp() {
        config = new AgentProperties.LlmResilienceConfig();
        AgentProperties.LlmRetryConfig retry = new AgentProperties.LlmRetryConfig();
        retry.setMaxAttempts(0); // 关闭重试，便于精确断言下游调用次数
        config.setRetry(retry);
        delegate = new RecordingDelegate();
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

    // ==================== LlmRateLimiter ====================

    @Test
    public void testRateLimiter_qpsExceeded_rejected() {
        LlmRateLimiter limiter = new LlmRateLimiter(1, 8);
        assertTrue("首次应放行", limiter.tryAcquire("t1:m"));
        limiter.release("t1:m");
        // 同秒内第二次（QPS=1）应被拒绝
        assertFalse("同秒第二个请求应被限流", limiter.tryAcquire("t1:m"));
    }

    @Test
    public void testRateLimiter_concurrencyExceeded_rejected_andReleased() {
        LlmRateLimiter limiter = new LlmRateLimiter(1000, 1);
        assertTrue(limiter.tryAcquire("t1:m"));
        // 并发已满（maxConcurrency=1），未 release 前应被拒绝
        assertFalse("并发满时应被拒绝", limiter.tryAcquire("t1:m"));
        limiter.release("t1:m");
        // release 后恢复
        assertTrue("release 后应恢复", limiter.tryAcquire("t1:m"));
        limiter.release("t1:m");
    }

    // ==================== LlmCircuitBreaker ====================

    @Test
    public void testCircuitBreaker_tripsAfterThreshold_thenShortCircuits() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(50, 3, 600_000); // 熔断 10 分钟
        // 前 3 次全部失败 → 错误率达到 100%，且达到 minRequests=3，触发熔断
        breaker.recordFailure("m1");
        breaker.recordFailure("m1");
        breaker.recordFailure("m1");
        assertTrue("错误率达到阈值应熔断", breaker.isOpen("m1"));
    }

    @Test
    public void testCircuitBreaker_lowFailureRate_notTripped() {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(50, 3, 600_000);
        breaker.recordSuccess("m1");
        breaker.recordFailure("m1");
        breaker.recordSuccess("m1");
        // 失败率 33% < 50%，不熔断
        assertFalse("失败率未达阈值不应熔断", breaker.isOpen("m1"));
    }

    @Test
    public void testCircuitBreaker_reopensAfterOpenWindow() throws InterruptedException {
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(50, 1, 30); // 熔断 30ms
        breaker.recordFailure("m1");
        assertTrue(breaker.isOpen("m1"));
        Thread.sleep(50);
        // 熔断到期 → 半开放行
        assertFalse("熔断到期应恢复放行", breaker.isOpen("m1"));
    }

    // ==================== ResilientLlmGateway 集成 ====================

    @Test
    public void testGateway_rateLimited_returnsErrorWithoutCallingDelegate() {
        ResilientLlmGateway gw = new ResilientLlmGateway(delegate, config, null,
                new LlmRateLimiter(1, 8), null);
        LlmResponse first = gw.chat(request(), model("primary"));
        assertTrue("首个请求应正常", first.getContent().startsWith("ok"));
        int countBefore = delegate.callCount("primary");
        LlmResponse limited = gw.chat(request(), model("primary"));
        assertEquals("error", limited.getFinishReason());
        assertEquals(ErrorCategory.TRANSIENT, limited.getErrorCategory());
        assertTrue("限流请求不应调用下游", limited.getContent().contains("限流"));
        assertEquals("限流请求不应命中下游", countBefore, delegate.callCount("primary"));
    }

    @Test
    public void testGateway_circuitOpen_shortCircuitsWithoutCallingDelegate() {
        int minRequests = 3;
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(50, minRequests, 600_000);
        ResilientLlmGateway gw = new ResilientLlmGateway(delegate, config, null, null, breaker);
        delegate.failAlways = true;
        // 触发熔断：minRequests 次失败
        for (int i = 0; i < minRequests; i++) {
            gw.chat(request(), model("primary"));
        }
        assertTrue("应已触发熔断", breaker.isOpen("primary"));
        int countBefore = delegate.callCount("primary");
        LlmResponse blocked = gw.chat(request(), model("primary"));
        assertEquals("error", blocked.getFinishReason());
        assertTrue("熔断应短路返回", blocked.getContent().contains("熔断"));
        assertEquals("熔断期不应调用下游", countBefore, delegate.callCount("primary"));
    }

    @Test
    public void testGateways_disabledByDefault_noImpact() {
        // 未传限流器/熔断器（null）时行为与改造前一致
        ResilientLlmGateway gw = new ResilientLlmGateway(delegate, config, null);
        LlmResponse r = gw.chat(request(), model("primary"));
        assertTrue(r.getContent().startsWith("ok"));
        assertEquals("正常调用 1 次", 1, delegate.callCount("primary"));
    }

    /** 记录各 model 调用次数，可按需抛瞬时错误 / 成功 */
    private static class RecordingDelegate implements LlmGateway {
        final Map<String, Integer> calls = new ConcurrentHashMap<>();
        boolean failAlways;

        int callCount(String model) {
            return calls.getOrDefault(model, 0);
        }

        private LlmResponse doCall(String model) {
            calls.merge(model, 1, Integer::sum);
            if (failAlways) {
                throw new RetryableLlmException("HTTP 500: upstream error");
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