package com.mwb.ai.claw.web;

import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.infrastructure.llm.RetryableLlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Web 层全局异常处理（替代 COLA CatchAndLog 的异常转换）：
 * 业务异常 → 失败 SingleResponse（HTTP 200，success=false，携带 errCode/errMessage）；
 * 其他异常 → 通用失败响应并记录错误日志。
 * <p>
 * C3 统一错误码（按 ErrorCategory 映射，主失败路径经 ChatCmdExe 转失败响应，此处为泄漏兜底）：
 * LLM_UNAVAILABLE / LLM_TIMEOUT / TOOL_TIMEOUT / RATE_LIMITED / BUDGET_EXCEEDED / SYSTEM_ERROR。
 */
@RestControllerAdvice
@Profile("web")
public class GlobalExceptionHandler {

    /** LLM 不可用（重试 + fallback 后仍失败） */
    public static final String LLM_UNAVAILABLE = "LLM_UNAVAILABLE";

    /** LLM 超时 */
    public static final String LLM_TIMEOUT = "LLM_TIMEOUT";

    /** 工具执行超时 */
    public static final String TOOL_TIMEOUT = "TOOL_TIMEOUT";

    /** 触发限流（LLM 429 重试耗尽） */
    public static final String RATE_LIMITED = "RATE_LIMITED";

    /** token 预算耗尽 */
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";

    /** 通用系统错误 */
    public static final String SYSTEM_ERROR = "SYSTEM_ERROR";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public SingleResponse<Void> handleBizException(BizException e) {
        log.warn("业务异常: errCode={}, errMessage={}", e.getErrCode(), e.getErrMessage());
        return SingleResponse.buildFailure(e.getErrCode(), e.getErrMessage());
    }

    @ExceptionHandler(RetryableLlmException.class)
    public SingleResponse<Void> handleRetryableLlmException(RetryableLlmException e) {
        // 瞬时 LLM 错误泄漏到 Web 层（如未被韧性装饰器拦截）→ LLM_UNAVAILABLE
        log.warn("LLM 瞬时错误泄漏到 Web 层: {}", e.getMessage());
        return SingleResponse.buildFailure(LLM_UNAVAILABLE, "LLM 调用失败: " + e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public SingleResponse<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return SingleResponse.buildFailure(SYSTEM_ERROR, "系统异常: " + e.getMessage());
    }
}
