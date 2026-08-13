package com.mwb.ai.claw.infrastructure.tool.builtin.dto;

import lombok.Data;

import java.util.Map;

/**
 * http 工具入参。
 */
@Data
public class HttpParams {

    /** 目标 URL */
    private String url;

    /** HTTP 方法：GET / POST */
    private String method;

    /** 可选请求头 */
    private Map<String, String> headers;

    /** POST 请求体 */
    private String body;
}
