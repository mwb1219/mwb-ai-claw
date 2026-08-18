package com.mwb.ai.claw.infrastructure.tool.builtin.dto;

import lombok.Data;

/**
 * shell_status 工具入参（查询 / 终止后台任务）。
 */
@Data
public class ShellStatusParams {

    /** 后台任务 ID（shell 工具返回的 taskId） */
    private String taskId;

    /** 操作：status（默认，查询状态）| output（获取完整输出）| kill（终止任务） */
    private String action;
}
