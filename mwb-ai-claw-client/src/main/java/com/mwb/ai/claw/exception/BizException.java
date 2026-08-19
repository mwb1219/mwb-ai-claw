package com.mwb.ai.claw.exception;

/**
 * 业务异常（替代 COLA BizException）：携带 errCode 与 errMessage，
 * 由 Web 层全局异常处理转换为失败响应。
 */
public class BizException extends RuntimeException {

    private final String errCode;

    public BizException(String errCode, String errMessage) {
        super(errMessage);
        this.errCode = errCode;
    }

    public String getErrCode() {
        return errCode;
    }

    public String getErrMessage() {
        return getMessage();
    }
}
