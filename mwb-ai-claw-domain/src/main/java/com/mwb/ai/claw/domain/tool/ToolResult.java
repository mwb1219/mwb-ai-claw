package com.mwb.ai.claw.domain.tool;

import lombok.Data;

/**
 * 工具执行结果值对象
 */
@Data
public class ToolResult {

    private boolean success;

    /** 工具输出（作为 Observation 反馈给 LLM） */
    private String output;

    private String error;

    public static ToolResult success(String output) {
        ToolResult r = new ToolResult();
        r.success = true;
        r.output = output;
        return r;
    }

    public static ToolResult error(String error) {
        ToolResult r = new ToolResult();
        r.success = false;
        r.error = error;
        return r;
    }
}
