package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.mwb.ai.claw.domain.memory.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * 读取长期记忆工具：默认返回全部结构化事实；传入 query 时按关键词检索召回相关记忆。
 */
@Component
public class ReadMemoryTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReadMemoryTool.class);
    private static final String NAME = "read_memory";
    private static final String PARAMS_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"query\":{\"type\":\"string\",\"description\":\"检索关键词，只返回相关的记忆（可选，缺省返回全部记忆）\"}"
            + "},"
            + "\"required\":[]"
            + "}";

    @Resource
    private LayeredMemoryGateway memoryGateway;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        return new ToolSpec(NAME,
                "读取长期记忆。不传 query 返回全部跨会话记忆；传 query 按关键词检索最相关的记忆片段。",
                PARAMS_SCHEMA);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        try {
            String query = null;
            if (argumentsJson != null && !argumentsJson.trim().isEmpty()) {
                query = JsonUtils.readTree(argumentsJson).path("query").asText(null);
            }
            if (query == null || query.trim().isEmpty()) {
                String facts = memoryGateway.readFactsText();
                if (facts.isEmpty() || "(暂无长期记忆)".equals(facts)) {
                    return ToolResult.success("(暂无长期记忆 — 可以调用 write_memory 工具写入内容)");
                }
                log.info("读取长期记忆: {} 字符", facts.length());
                return ToolResult.success(facts);
            }
            List<MemoryPage> hits = memoryGateway.search(query.trim(), 5);
            if (hits.isEmpty()) {
                return ToolResult.success("(未检索到与 \"" + query + "\" 相关的记忆)");
            }
            StringBuilder sb = new StringBuilder("检索「" + query + "」相关记忆：\n");
            for (MemoryPage page : hits) {
                if (page.getKey() != null) {
                    sb.append("- ").append(page.getKey()).append("：").append(page.getContent()).append("\n");
                } else {
                    sb.append("- ").append(page.getContent()).append("\n");
                }
            }
            return ToolResult.success(sb.toString().trim());
        } catch (Exception e) {
            log.error("读取长期记忆失败", e);
            return ToolResult.error("读取记忆失败: " + e.getMessage());
        }
    }
}
