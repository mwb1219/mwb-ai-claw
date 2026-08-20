package com.mwb.ai.claw.infrastructure.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * OpenAI /chat/completions 响应体（非流式）。
 */
@Data
public class ChatCompletionResponse {

    private List<ChatChoice> choices;

    /** 用量统计（非流式响应由服务端返回；流式响应部分提供商在最后 chunk 返回） */
    private ChatUsage usage;
}
