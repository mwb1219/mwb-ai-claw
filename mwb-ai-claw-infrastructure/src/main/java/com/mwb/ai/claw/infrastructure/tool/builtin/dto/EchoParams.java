package com.mwb.ai.claw.infrastructure.tool.builtin.dto;

import lombok.Data;

/**
 * echo 工具入参。
 */
@Data
public class EchoParams {

    /** 要回显的文本内容 */
    private String text;
}
