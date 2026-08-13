package com.mwb.ai.claw.infrastructure.tool.builtin.dto;

import lombok.Data;

/**
 * file 工具入参。
 */
@Data
public class FileParams {

    /** 操作类型：read / write / list */
    private String action;

    /** 文件或目录路径 */
    private String path;

    /** write 操作时的文件内容 */
    private String content;
}
