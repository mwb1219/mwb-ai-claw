package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.infrastructure.tool.builtin.dto.EchoParams;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.springframework.stereotype.Component;

/**
 * 内置回显工具：将传入文本原样返回，用于演示工具调用闭环。
 */
@Component
public class EchoTool implements ToolExecutor {

    private static final String NAME = "echo";

    private static final String PARAMS_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"text\":{\"type\":\"string\",\"description\":\"要回显的文本内容\"}"
            + "},"
            + "\"required\":[\"text\"]"
            + "}";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        return new ToolSpec(NAME, "回显工具：将传入的 text 原样返回，用于演示工具调用能力。", PARAMS_SCHEMA);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        try {
            EchoParams params = JsonUtils.fromJson(argumentsJson == null ? "{}" : argumentsJson, EchoParams.class);
            String text = params.getText() == null ? "" : params.getText();
            return ToolResult.success("echo: " + text);
        } catch (Exception e) {
            return ToolResult.error("参数解析失败: " + e.getMessage());
        }
    }
}
