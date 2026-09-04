package com.mwb.ai.claw.dto;

import lombok.Data;

/**
 * 编排运行续跑命令（H1-P1 可中断恢复）：
 * 凭 runId 从上次挂起处（人工门禁）继续推进。orchestrationId 可选，
 * 缺省时从运行记录解析（便于调用方只凭 runId 续跑）。
 */
@Data
public class ResumeCmd {

    /** 运行记录 id（必须属于当前 scope） */
    private String runId;

    /** 会话 id（可选；缺省沿用运行记录中的会话） */
    private String sessionId;

    /** 编排定义 id（可选；缺省从运行记录解析） */
    private String orchestrationId;
}