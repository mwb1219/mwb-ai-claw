package com.mwb.ai.claw.infrastructure.tool.builtin.dto;

import lombok.Data;

/**
 * write_memory 工具入参（结构化事实写入）。
 */
@Data
public class WriteMemoryParams {

    /** 记忆内容（必填） */
    private String content;

    /** 主题分类（可选，用于去重合并，如 "用户偏好-语言"） */
    private String topic;

    /** 重要度 0-1（可选，默认 0.8；低于阈值时可能被丢弃） */
    private Double importance;
}
