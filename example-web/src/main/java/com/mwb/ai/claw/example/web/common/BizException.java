package com.mwb.ai.claw.example.web.common;

/**
 * 业务异常：携带错误码，由 Controller 捕获后转为 {@code SingleResponse.buildFailure}。
 */
public class BizException extends RuntimeException {

    private final String errCode;

    public BizException(String errCode, String message) {
        super(message);
        this.errCode = errCode;
    }

    public String getErrCode() {
        return errCode;
    }
}
