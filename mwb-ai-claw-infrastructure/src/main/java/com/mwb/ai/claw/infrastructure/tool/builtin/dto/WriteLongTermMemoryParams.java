package com.mwb.ai.claw.infrastructure.tool.builtin.dto;

import lombok.Data;

/**
 * write_long_term_memory 工具入参（MEMORY.md 用户画像写入）。
 */
@Data
public class WriteLongTermMemoryParams {

    /** 用户画像内容（身份/姓名/职业、风格偏好、关注领域）（必填） */
    private String content;
}