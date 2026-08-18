package com.mwb.ai.claw.domain.core;

/**
 * 会话消息角色（已废弃：{@link Message} 的消息角色改用 String 表示，本枚举全项目无引用）。
 *
 * @deprecated 历史遗留枚举，后续可删除
 */
@Deprecated
public enum MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}
