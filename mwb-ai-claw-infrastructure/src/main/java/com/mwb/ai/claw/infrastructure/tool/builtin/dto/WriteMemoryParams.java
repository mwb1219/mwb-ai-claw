package com.mwb.ai.claw.infrastructure.tool.builtin.dto;

import lombok.Data;

/**
 * write_memory 工具入参。
 */
@Data
public class WriteMemoryParams {

    /** 要写入 MEMORY.md 的记忆内容（Markdown 格式） */
    private String content;
}
