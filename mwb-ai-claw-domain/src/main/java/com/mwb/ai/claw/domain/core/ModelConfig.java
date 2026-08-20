package com.mwb.ai.claw.domain.core;

import lombok.Data;

/**
 * 模型配置值对象
 */
@Data
public class ModelConfig {

    /** 模型标识，如 gpt-4o / deepseek-chat / qwen-plus */
    private String model;

    /** Provider 类型：openai / anthropic / gemini / ollama（null 或未知默认 openai，向后兼容） */
    private String provider;

    /** OpenAI 兼容的 API Base URL（空则由 Provider 推断默认） */
    private String baseUrl;

    /** API Key */
    private String apiKey;

    /** 采样温度 */
    private double temperature = 0.7;

    /** 单次最大 tokens */
    private int maxTokens = 2048;

    /**
     * 思考模式开关（null=不传，由模型提供方默认；false=关闭思考直接输出，如 DeepSeek
     * 的 {"thinking":{"type":"disabled"}}；true=显式开启思考）。
     */
    private Boolean thinking;
}
