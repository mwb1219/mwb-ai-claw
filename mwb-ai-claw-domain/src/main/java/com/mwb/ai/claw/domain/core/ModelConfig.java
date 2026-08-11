package com.mwb.ai.claw.domain.core;

import lombok.Data;

/**
 * 模型配置值对象
 */
@Data
public class ModelConfig {

    /** 模型标识，如 gpt-4o / deepseek-chat / qwen-plus */
    private String model;

    /** OpenAI 兼容的 API Base URL */
    private String baseUrl;

    /** API Key */
    private String apiKey;

    /** 采样温度 */
    private double temperature = 0.7;

    /** 单次最大 tokens */
    private int maxTokens = 2048;
}
