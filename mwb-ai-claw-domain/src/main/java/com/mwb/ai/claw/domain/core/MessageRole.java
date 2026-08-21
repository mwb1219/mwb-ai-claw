package com.mwb.ai.claw.domain.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 会话消息角色。
 * <p>
 * JSON 序列化使用小写字符串（system / user / assistant / tool），
 * 与 LLM 供应商消息角色及既有持久化会话数据保持一致。
 */
public enum MessageRole {

    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String value;

    MessageRole(String value) {
        this.value = value;
    }

    /** 角色的小写字符串表示（LLM / JSON 线上格式） */
    @JsonValue
    public String getValue() {
        return value;
    }

    /** 从小写字符串反序列化（大小写不敏感，兼容历史数据） */
    @JsonCreator
    public static MessageRole fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (MessageRole role : values()) {
            if (role.value.equalsIgnoreCase(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("未知的消息角色: " + value);
    }
}
