package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 写入长期记忆工具：将内容保存到 MEMORY.md，跨会话持久化。
 */
@Component
public class WriteMemoryTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(WriteMemoryTool.class);
    private static final String NAME = "write_memory";
    private static final String PARAMS_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"content\":{\"type\":\"string\",\"description\":\"要写入 MEMORY.md 的记忆内容（Markdown 格式）\"}"
            + "},"
            + "\"required\":[\"content\"]"
            + "}";

    private final ObjectMapper mapper = new ObjectMapper();

    @Resource
    private LongTermMemoryGateway memoryGateway;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        return new ToolSpec(NAME,
                "写入长期记忆到 MEMORY.md 文件。适合保存用户偏好、项目上下文、重要决策等跨会话需要记住的信息。"
                        + " 传入的 content 将覆盖整个文件内容，请确保包含所有需要保留的信息。",
                PARAMS_SCHEMA);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        try {
            JsonNode node = mapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
            JsonNode contentNode = node.get("content");
            if (contentNode == null || contentNode.asText().trim().isEmpty()) {
                return ToolResult.error("参数 content 不能为空");
            }
            String content = contentNode.asText();
            memoryGateway.saveMemory(content);
            log.info("长期记忆已更新: {} 字符", content.length());
            return ToolResult.success("记忆已保存到 MEMORY.md（" + content.length() + " 字符）");
        } catch (Exception e) {
            log.error("写入长期记忆失败", e);
            return ToolResult.error("保存记忆失败: " + e.getMessage());
        }
    }
}
