package com.mwb.ai.claw.domain.tool;

import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;

import java.util.List;

/**
 * 工具网关接口：抽象工具执行能力（依赖倒置）
 */
public interface ToolGateway {

    /**
     * 执行指定工具
     *
     * @param toolName      工具名称
     * @param argumentsJson 入参 JSON 字符串
     */
    ToolResult execute(String toolName, String argumentsJson);

    /**
     * 列出所有已注册工具规格
     */
    List<ToolSpec> listTools();

    /**
     * 查询指定工具规格
     */
    ToolSpec getToolSpec(String toolName);
}
