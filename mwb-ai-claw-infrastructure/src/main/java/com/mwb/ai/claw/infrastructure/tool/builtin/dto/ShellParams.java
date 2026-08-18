package com.mwb.ai.claw.infrastructure.tool.builtin.dto;

import lombok.Data;

/**
 * shell 工具入参。
 */
@Data
public class ShellParams {

    /** 要执行的 Shell 命令 */
    private String command;

    /** 可选的工作目录 */
    private String workingDir;

    /** 是否后台运行：true 时立即返回 taskId 不等待执行完成，可用 shell_status 查询/终止 */
    private Boolean background;
}
