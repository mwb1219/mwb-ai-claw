package com.mwb.ai.claw.domain.llm;

import com.mwb.ai.claw.domain.tool.ToolSpec;
import lombok.Data;

import java.util.List;

/**
 * LLM 请求值对象
 */
@Data
public class LlmRequest {

    /** 模型标识 */
    private String model;

    /** 消息列表 */
    private List<LlmMessage> messages;

    /** 可用工具规格 */
    private List<ToolSpec> tools;

    /** 采样温度 */
    private double temperature;

    /** 最大 tokens */
    private int maxTokens;

    /** 思考模式开关（null=不传，由模型提供方默认） */
    private Boolean thinking;
}
