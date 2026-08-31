package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.mwb.ai.claw.domain.memory.MemoryStrategy;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.infrastructure.tool.builtin.dto.WriteMemoryParams;
import com.mwb.ai.claw.domain.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 写入长期记忆工具：以结构化事实（topic + content + importance）写入分层记忆 facts。
 * <p>
 * 同 topic 会自动合并去重（保留重要度更高者），低于阈值的低价值记忆会被丢弃。
 */
@Component
public class WriteMemoryTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(WriteMemoryTool.class);
    private static final String NAME = "write_memory";
    private static final String PARAMS_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"content\":{\"type\":\"string\",\"description\":\"要记住的记忆内容（跨会话保留，如用户偏好、项目背景、重要决策）\"},"
            + "\"topic\":{\"type\":\"string\",\"description\":\"主题分类，用于去重合并，如 用户偏好-语言（可选）\"},"
            + "\"importance\":{\"type\":\"number\",\"description\":\"重要度 0-1，默认 0.8（可选）\"}"
            + "},"
            + "\"required\":[\"content\"]"
            + "}";

    @Resource
    private MemoryStrategy memoryStrategy;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        return new ToolSpec(NAME,
                "写入长期记忆（结构化事实）。适合保存用户偏好、项目上下文、重要决策等跨会话需要记住的信息。"
                        + " 建议提供 topic 便于去重合并，重要度低于阈值（默认 0.6）的记忆会被丢弃。",
                PARAMS_SCHEMA);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        try {
            WriteMemoryParams params = JsonUtils.fromJson(argumentsJson == null ? "{}" : argumentsJson, WriteMemoryParams.class);
            String content = params.getContent();
            if (content == null || content.trim().isEmpty()) {
                return ToolResult.error("参数 content 不能为空");
            }
            String topic = params.getTopic();
            if (topic == null || topic.trim().isEmpty()) {
                topic = "长期记忆-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd-HHmmss"));
            }
            double importance = params.getImportance() == null ? 0.8 : params.getImportance();
            memoryStrategy.saveMemory(topic, content.trim(), importance);
            log.info("长期记忆已写入: topic={}, {} 字符", topic, content.length());
            return ToolResult.success("记忆已保存（主题: " + topic + "）");
        } catch (Exception e) {
            log.error("写入长期记忆失败", e);
            return ToolResult.error("保存记忆失败: " + e.getMessage());
        }
    }
}
