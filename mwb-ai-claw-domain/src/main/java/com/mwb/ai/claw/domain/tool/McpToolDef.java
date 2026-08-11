package com.mwb.ai.claw.domain.tool;

import lombok.Data;

/**
 * MCP 工具定义值对象
 * <p>
 * 对应 MCP 协议 tools/list 返回的工具定义，
 * 可转换为 {@link ToolSpec}。
 */
@Data
public class McpToolDef {

    /** 工具名称 */
    private String name;

    /** 工具描述 */
    private String description;

    /** 参数 JSON Schema（字符串形式） */
    private String inputSchema;
}
