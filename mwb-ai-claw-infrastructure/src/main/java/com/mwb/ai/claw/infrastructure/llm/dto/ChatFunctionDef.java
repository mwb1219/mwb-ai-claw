package com.mwb.ai.claw.infrastructure.llm.dto;

import lombok.Data;

import java.util.Map;

/**
 * OpenAI 请求中的函数定义（function 字段）。
 */
@Data
public class ChatFunctionDef {

    private String name;

    private String description;

    /** 参数 JSON Schema（动态结构） */
    private Map<String, Object> parameters;
}
