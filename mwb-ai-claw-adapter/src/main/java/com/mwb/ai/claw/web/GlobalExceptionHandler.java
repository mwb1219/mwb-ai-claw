package com.mwb.ai.claw.web;

import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Web 层全局异常处理（替代 COLA CatchAndLog 的异常转换）：
 * 业务异常 → 失败 SingleResponse（HTTP 200，success=false，携带 errCode/errMessage）；
 * 其他异常 → 通用失败响应并记录错误日志。
 */
@RestControllerAdvice
@Profile("web")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public SingleResponse<Void> handleBizException(BizException e) {
        log.warn("业务异常: errCode={}, errMessage={}", e.getErrCode(), e.getErrMessage());
        return SingleResponse.buildFailure(e.getErrCode(), e.getErrMessage());
    }

    @ExceptionHandler(Exception.class)
    public SingleResponse<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return SingleResponse.buildFailure("SYSTEM_ERROR", "系统异常: " + e.getMessage());
    }
}
