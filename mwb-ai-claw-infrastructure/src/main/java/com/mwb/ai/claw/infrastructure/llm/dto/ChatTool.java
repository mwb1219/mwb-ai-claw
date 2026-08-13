package com.mwb.ai.claw.infrastructure.llm.dto;

import lombok.Data;

/**
 * OpenAI 请求中的工具定义（tools 数组元素）。
 */
@Data
public class ChatTool {

    /** 固定为 "function" */
    private String type;

    private ChatFunctionDef function;
}
