package com.mwb.ai.claw.infrastructure.llm;

/**
 * LLM 瞬时错误（可重试）：HTTP 429 / 5xx、连接或读超时、网络 IOException。
 * <p>
 * 由 {@link LlmGatewayImpl} 抛出，由 {@link ResilientLlmGateway} 捕获并执行
 * 指数退避重试 / 备用模型降级。业务错误（4xx 除 429）不抛出，返回
 * {@code finishReason=error} 的响应。
 */
public class RetryableLlmException extends RuntimeException {

    public RetryableLlmException(String message) {
        super(message);
    }

    public RetryableLlmException(String message, Throwable cause) {
        super(message, cause);
    }

    public RetryableLlmException(Throwable cause) {
        super(cause.getMessage(), cause);
    }
}
