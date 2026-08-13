package com.mwb.ai.claw.infrastructure.llm.dto;

import lombok.Data;

/**
 * OpenAI 函数调用（工具调用中的 function 字段）。
 */
@Data
public class ChatFunctionCall {

    private String name;

    private String arguments;
}
