package com.mwb.ai.claw.infrastructure.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.core.ErrorCategory;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;
import com.mwb.ai.claw.domain.util.TokenEstimator;

/**
 * LLM 韧性装饰器：包装主 {@link LlmGateway}，提供请求级限流、模型级熔断、重试退避、
 * 备用模型降级与 token 预算保护。
 * <p>
 * - 重试：仅对瞬时错误（HTTP 429 / 5xx / 连接与读超时 / 网络 IOException，由
 *   {@link RetryableLlmException} 标识）进行指数退避 + 抖动重试；业务错误（4xx）不重试。
 * - 降级：主模型重试耗尽后，若配置了 {@code agent.llm.fallback-model}，用备用模型发起一次（不重试）。
 * - 限流（T7）：按 tenant+model 维度 QPS + 并发数限制，超限返回 429 语义错误。
 * - 熔断（T7）：某模型窗口内错误率超阈值自动熔断 {@code openMs} 毫秒，期间短路请求。
 * - 预算：每次调用成功后按响应 usage（缺失时估算）累计到当前线程的 {@link RunTokenBudget}，
 *   超限即中止并返回明确错误，避免单次运行 token 失控。
 * - 单消息截断：请求中单条消息超过 {@code agent.llm.max-single-message-tokens} 时按估算截断并 WARN。
 * <p>
 * 默认实现（LlmGatewayImpl）不经此类修改，保持 POJO 可替换性（Phase B 原则）。
 */
public class ResilientLlmGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(ResilientLlmGateway.class);

    private final LlmGateway delegate;
    private final AgentProperties.LlmResilienceConfig config;
    private final MetricsRecorder metrics;
    private final LlmRateLimiter rateLimiter;
    private final LlmCircuitBreaker circuitBreaker;
    private final Random random = new Random();

    public ResilientLlmGateway(LlmGateway delegate, AgentProperties.LlmResilienceConfig config,
                               MetricsRecorder metrics) {
        this(delegate, config, metrics, null, null);
    }

    /** T7：传入限流器 / 熔断器实例（null 表示对应保护关闭）。 */
    public ResilientLlmGateway(LlmGateway delegate, AgentProperties.LlmResilienceConfig config,
                               MetricsRecorder metrics, LlmRateLimiter rateLimiter, LlmCircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.config = config;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public LlmResponse chat(LlmRequest request, ModelConfig modelConfig) {
        LlmRequest req = sanitize(request);
        String key = rateKey(modelConfig);
        if (circuitBreaker != null && circuitBreaker.isOpen(modelConfig.getModel())) {
            log.warn("LLM 模型熔断打开，短路请求: model={}", modelConfig.getModel());
            return errorResponse("模型熔断中（" + modelConfig.getModel() + "），请稍后重试");
        }
        if (rateLimiter != null && !rateLimiter.tryAcquire(key)) {
            log.warn("LLM 请求触发限流(429): key={}", key);
            return errorResponse("请求过于频繁，已触发限流，请稍后重试");
        }
        try {
            int maxAttempts = config.getRetry().getMaxAttempts();
            int attempt = 0;
            while (true) {
                try {
                    LlmResponse resp = consumeBudget(req, delegate.chat(req, modelConfig), modelConfig);
                    if (circuitBreaker != null) {
                        circuitBreaker.recordSuccess(modelConfig.getModel());
                    }
                    return resp;
                } catch (RetryableLlmException e) {
                    // 任务被取消（断连回收）：不重试，避免取消后继续消耗 token
                    if (Thread.currentThread().isInterrupted()) {
                        log.warn("LLM 调用因任务被取消而放弃重试: model={}, err={}",
                                modelConfig.getModel(), e.getMessage());
                        if (circuitBreaker != null) {
                            circuitBreaker.recordFailure(modelConfig.getModel());
                        }
                        return errorResponse("已取消");
                    }
                    if (circuitBreaker != null) {
                        circuitBreaker.recordFailure(modelConfig.getModel());
                    }
                    attempt++;
                    if (attempt > maxAttempts) {
                        return fallback(req, modelConfig, e);
                    }
                    long backoff = backoff(attempt);
                    log.warn("LLM 调用重试: model={}, attempt={}/{}（0=关闭后不重试）, 原因={}, 下次退避={}ms",
                            modelConfig.getModel(), attempt, maxAttempts, e.getMessage(), backoff);
                    if (metrics != null) {
                        metrics.llmRetry(modelConfig.getModel(), attempt);
                    }
                    sleep(backoff);
                }
            }
        } finally {
            if (rateLimiter != null) {
                rateLimiter.release(key);
            }
        }
    }

    @Override
    public LlmResponse streamChat(LlmRequest request, ModelConfig modelConfig, LlmStreamCallback callback) {
        LlmRequest req = sanitize(request);
        String key = rateKey(modelConfig);
        if (circuitBreaker != null && circuitBreaker.isOpen(modelConfig.getModel())) {
            log.warn("LLM 流式模型熔断打开，短路请求: model={}", modelConfig.getModel());
            if (callback != null) {
                callback.onError(new RetryableLlmException("模型熔断中（" + modelConfig.getModel() + "），请稍后重试"));
            }
            return errorResponse("模型熔断中（" + modelConfig.getModel() + "），请稍后重试");
        }
        if (rateLimiter != null && !rateLimiter.tryAcquire(key)) {
            log.warn("LLM 流式请求触发限流(429): key={}", key);
            if (callback != null) {
                callback.onError(new RetryableLlmException("请求过于频繁，已触发限流，请稍后重试"));
            }
            return errorResponse("请求过于频繁，已触发限流，请稍后重试");
        }
        try {
            int maxAttempts = config.getRetry().getMaxAttempts();
            int attempt = 0;
            while (true) {
                try {
                    LlmResponse resp = consumeBudget(req, delegate.streamChat(req, modelConfig, callback), modelConfig);
                    if (circuitBreaker != null) {
                        circuitBreaker.recordSuccess(modelConfig.getModel());
                    }
                    return resp;
                } catch (RetryableLlmException e) {
                    // 任务被取消（断连回收）：不重试，避免取消后继续消耗 token
                    if (Thread.currentThread().isInterrupted()) {
                        log.warn("LLM 流式调用因任务被取消而放弃重试: model={}, err={}",
                                modelConfig.getModel(), e.getMessage());
                        if (circuitBreaker != null) {
                            circuitBreaker.recordFailure(modelConfig.getModel());
                        }
                        return errorResponse("已取消");
                    }
                    if (circuitBreaker != null) {
                        circuitBreaker.recordFailure(modelConfig.getModel());
                    }
                    attempt++;
                    if (attempt > maxAttempts) {
                        return fallbackStream(req, modelConfig, callback, e);
                    }
                    long backoff = backoff(attempt);
                    log.warn("LLM 流式调用重试: model={}, attempt={}/{}（0=关闭后不重试）, 原因={}, 下次退避={}ms",
                            modelConfig.getModel(), attempt, maxAttempts, e.getMessage(), backoff);
                    if (metrics != null) {
                        metrics.llmRetry(modelConfig.getModel(), attempt);
                    }
                    sleep(backoff);
                }
            }
        } finally {
            if (rateLimiter != null) {
                rateLimiter.release(key);
            }
        }
    }

    /**
     * 主模型重试耗尽后降级：配置了备用模型则用备用配置发起一次（不重试，避免叠加延迟）。
     */
    private LlmResponse fallback(LlmRequest request, ModelConfig primary, RetryableLlmException last) {
        ModelConfig fb = fallbackConfig(primary);
        if (fb == null) {
            log.error("LLM 调用重试耗尽仍失败: model={}, err={}", primary.getModel(), last.getMessage());
            return errorResponse(last.getMessage());
        }
        log.warn("LLM 主模型重试耗尽，降级到备用模型: {} → {}, 原因={}",
                primary.getModel(), fb.getModel(), last.getMessage());
        try {
            return consumeBudget(request, delegate.chat(request, fb), fb);
        } catch (RetryableLlmException fe) {
            log.error("LLM 备用模型也失败: model={}, err={}", fb.getModel(), fe.getMessage());
            return errorResponse(fe.getMessage());
        }
    }

    private LlmResponse fallbackStream(LlmRequest request, ModelConfig primary,
                                       LlmStreamCallback callback, RetryableLlmException last) {
        ModelConfig fb = fallbackConfig(primary);
        if (fb == null) {
            log.error("LLM 流式调用重试耗尽仍失败: model={}, err={}", primary.getModel(), last.getMessage());
            if (callback != null) {
                callback.onError(last);
            }
            return errorResponse(last.getMessage());
        }
        log.warn("LLM 流式主模型重试耗尽，降级到备用模型: {} → {}, 原因={}",
                primary.getModel(), fb.getModel(), last.getMessage());
        try {
            return consumeBudget(request, delegate.streamChat(request, fb, callback), fb);
        } catch (RetryableLlmException fe) {
            log.error("LLM 流式备用模型也失败: model={}, err={}", fb.getModel(), fe.getMessage());
            if (callback != null) {
                callback.onError(fe);
            }
            return errorResponse(fe.getMessage());
        }
    }

    /** 组装备用模型配置；未配置 fallback-model 时返回 null（关闭降级） */
    private ModelConfig fallbackConfig(ModelConfig primary) {
        if (config.getFallbackModel() == null || config.getFallbackModel().trim().isEmpty()) {
            return null;
        }
        ModelConfig fb = new ModelConfig();
        fb.setModel(config.getFallbackModel().trim());
        fb.setBaseUrl(hasText(config.getFallbackBaseUrl()) ? config.getFallbackBaseUrl().trim() : primary.getBaseUrl());
        fb.setApiKey(hasText(config.getFallbackApiKey()) ? config.getFallbackApiKey().trim() : primary.getApiKey());
        fb.setTemperature(primary.getTemperature());
        fb.setMaxTokens(primary.getMaxTokens());
        fb.setThinking(primary.getThinking());
        return fb;
    }

    /**
     * 单条消息超长截断：估算 token 超过 max-single-message-tokens 时按内容截断并 WARN，
     * 避免超长输入撑爆上下文 / 超预算。
     */
    private LlmRequest sanitize(LlmRequest request) {
        int maxTokens = config.getMaxSingleMessageTokens();
        if (maxTokens <= 0 || request.getMessages() == null || request.getMessages().isEmpty()) {
            return request;
        }
        boolean changed = false;
        List<LlmMessage> msgs = new ArrayList<>(request.getMessages().size());
        for (LlmMessage m : request.getMessages()) {
            if (m.getContent() != null && TokenEstimator.estimate(m.getContent()) > maxTokens) {
                changed = true;
                log.warn("单条消息超过 max-single-message-tokens（{}，估算 {}），已截断: role={}, 原始长度={}",
                        maxTokens, TokenEstimator.estimate(m.getContent()), m.getRole(), m.getContent().length());
                LlmMessage copy = new LlmMessage();
                copy.setRole(m.getRole());
                copy.setContent(truncateContent(m.getContent(), maxTokens));
                copy.setToolCalls(m.getToolCalls());
                copy.setToolCallId(m.getToolCallId());
                msgs.add(copy);
                continue;
            }
            msgs.add(m);
        }
        if (!changed) {
            return request;
        }
        LlmRequest copy = new LlmRequest();
        copy.setModel(request.getModel());
        copy.setMessages(msgs);
        copy.setTools(request.getTools());
        copy.setTemperature(request.getTemperature());
        copy.setMaxTokens(request.getMaxTokens());
        copy.setThinking(request.getThinking());
        return copy;
    }

    /** 截断内容，保证截断后估算 token ≤ 上限 */
    private String truncateContent(String content, int maxTokens) {
        int est = TokenEstimator.estimate(content);
        if (est <= maxTokens) {
            return content;
        }
        // 粗截断（按最坏 4 字符/token 估算），再二次收敛
        String trimmed = content;
        int keep = Math.max(8, maxTokens * 4);
        while (trimmed.length() > keep) {
            trimmed = trimmed.substring(0, keep);
        }
        while (TokenEstimator.estimate(trimmed) > maxTokens && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() / 2);
        }
        return trimmed + "...";
    }

    /**
     * token 预算消费：当前线程绑定 RunTokenBudget 且超限时，返回预算耗尽错误并中止本次运行。
     */
    private LlmResponse consumeBudget(LlmRequest request, LlmResponse response, ModelConfig modelConfig) {
        RunTokenBudget budget = RunTokenBudget.current();
        if (response == null || budget == null || budget.isUnlimited()) {
            return response;
        }
        int prompt = response.getPromptTokens() != null
                ? response.getPromptTokens()
                : (int) estimatePrompt(request);
        int completion = response.getCompletionTokens() != null
                ? response.getCompletionTokens()
                : (response.getContent() == null ? 0 : TokenEstimator.estimate(response.getContent()));
        if (!budget.tryConsume(prompt + completion)) {
            log.warn("单次运行 token 预算已超限: limit={}, 已消耗={}, 本次请求={}, model={}",
                    budget.getLimit(), budget.getConsumed(), prompt + completion, modelConfig.getModel());
            return budgetExceededResponse(budget);
        }
        return response;
    }

    private LlmResponse budgetExceededResponse(RunTokenBudget budget) {
        LlmResponse r = new LlmResponse();
        r.setContent("已达到本次运行 token 预算上限（" + budget.getLimit() + " tokens），已中止执行。");
        r.setFinishReason("error");
        r.setErrorCategory(ErrorCategory.BUDGET);
        return r;
    }

    private LlmResponse errorResponse(String message) {
        LlmResponse r = new LlmResponse();
        r.setContent("LLM 调用失败: " + message);
        r.setFinishReason("error");
        r.setErrorCategory(ErrorCategory.TRANSIENT);
        return r;
    }

    /** 指数退避 + 抖动：backoff = min(initial * 2^(attempt-1), max) * (0.8 ~ 1.2) */
    private long backoff(int attempt) {
        AgentProperties.LlmRetryConfig retry = config.getRetry();
        long base = Math.min(retry.getInitialBackoffMs() * (1L << (attempt - 1)), retry.getMaxBackoffMs());
        double jitter = 0.8 + random.nextDouble() * 0.4;
        return Math.max(1, (long) (base * jitter));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM 重试等待被中断", e);
        }
    }

    private long estimatePrompt(LlmRequest request) {
        if (request.getMessages() == null) {
            return 0;
        }
        long total = 0;
        for (LlmMessage msg : request.getMessages()) {
            total += TokenEstimator.estimate(msg.getContent());
        }
        return total;
    }

    private boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** 限流 key：tenant + model，未登录/匿名时为默认租户空串兜底。 */
    private String rateKey(ModelConfig modelConfig) {
        String tenant = AgentScopeContext.get() == null ? "" : AgentScopeContext.get().getTenantId();
        return (tenant == null ? "" : tenant) + ":" + modelConfig.getModel();
    }
}
