package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 读取长期记忆工具：返回 MEMORY.md 的全部内容。
 */
@Component
public class ReadMemoryTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReadMemoryTool.class);
    private static final String NAME = "read_memory";
    private static final String PARAMS_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    @Resource
    private LongTermMemoryGateway memoryGateway;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        return new ToolSpec(NAME,
                "读取长期记忆文件 MEMORY.md 的内容。该文件存储跨会话的持久信息，如用户偏好、项目上下文等。",
                PARAMS_SCHEMA);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        String content = memoryGateway.loadMemory();
        if (content == null || content.trim().isEmpty()) {
            return ToolResult.success("(MEMORY.md 不存在或为空 — 可以调用 write_memory 工具写入内容)");
        }
        log.info("读取长期记忆: {} 字符", content.length());
        return ToolResult.success(content);
    }
}
