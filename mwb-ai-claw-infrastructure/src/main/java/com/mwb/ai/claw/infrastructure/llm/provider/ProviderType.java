package com.mwb.ai.claw.infrastructure.llm.provider;

/**
 * LLM Provider 类型（D1 多 Provider 适配）。
 * <p>
 * 路由依据：{@code ModelConfig.provider}（未配置或未知时默认 OPENAI，完全向后兼容）。
 */
public enum ProviderType {

    OPENAI,
    ANTHROPIC,
    GEMINI,
    OLLAMA;

    /** 解析字符串为 ProviderType（null / 空白 / 未知 → OPENAI） */
    public static ProviderType fromString(String s) {
        if (s == null || s.trim().isEmpty()) {
            return OPENAI;
        }
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OPENAI;
        }
    }

    /** 该 Provider 的默认 Base URL（未显式配置时使用） */
    public String defaultBaseUrl() {
        switch (this) {
            case ANTHROPIC:
                return "https://api.anthropic.com/v1";
            case GEMINI:
                return "https://generativelanguage.googleapis.com/v1beta";
            case OLLAMA:
                return "http://localhost:11434/v1";
            default:
                return "https://api.openai.com/v1";
        }
    }
}
