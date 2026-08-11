package com.mwb.ai.claw.domain.tool;

import lombok.Data;

/**
 * MCP Server 配置值对象
 * <p>
 * 用于描述一个外部 MCP Server 的连接方式。
 */
@Data
public class McpServerConfig {

    /** Server 名称（唯一标识） */
    private String name;

    /** 传输类型：stdio 或 sse */
    private String transport = "stdio";

    /** stdio 模式：要执行的命令（如 npx、node、python3） */
    private String command;

    /** stdio 模式：命令参数 */
    private java.util.List<String> args = new java.util.ArrayList<>();

    /** stdio 模式：环境变量 */
    private java.util.Map<String, String> env = new java.util.HashMap<>();

    /** sse 模式：Server URL */
    private String url;

    /** sse 模式：请求头 */
    private java.util.Map<String, String> headers = new java.util.HashMap<>();

    /** 是否启用 */
    private boolean enabled = true;
}
