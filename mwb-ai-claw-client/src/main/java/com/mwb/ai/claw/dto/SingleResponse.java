package com.mwb.ai.claw.dto;

import lombok.Data;

/**
 * 统一响应包装（替代 COLA SingleResponse，JSON 契约保持兼容：success / data / code / errCode / errMessage）
 */
@Data
public class SingleResponse<T> {

    private boolean success;

    /** 预留状态码（成功为空，失败可携带细分错误码，前端暂未使用） */
    private String code;

    private String errCode;

    private String errMessage;

    private T data;

    public static <T> SingleResponse<T> of(T data) {
        SingleResponse<T> resp = new SingleResponse<>();
        resp.setSuccess(true);
        resp.setData(data);
        return resp;
    }

    public static <T> SingleResponse<T> buildSuccess() {
        SingleResponse<T> resp = new SingleResponse<>();
        resp.setSuccess(true);
        return resp;
    }

    public static <T> SingleResponse<T> buildFailure(String errCode, String errMessage) {
        SingleResponse<T> resp = new SingleResponse<>();
        resp.setSuccess(false);
        resp.setErrCode(errCode);
        resp.setErrMessage(errMessage);
        return resp;
    }
}
