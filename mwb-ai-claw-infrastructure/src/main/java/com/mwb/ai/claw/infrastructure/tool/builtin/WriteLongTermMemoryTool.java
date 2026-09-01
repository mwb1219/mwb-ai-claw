package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.infrastructure.memory.longterm.LongTermMemoryWriter;
import com.mwb.ai.claw.infrastructure.tool.builtin.dto.WriteLongTermMemoryParams;
import com.mwb.ai.claw.domain.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 写入 MEMORY.md 长期记忆工具（T8-B LLM 自判断）：
 * 让 LLM 在对话中主动判断「这条信息值得作为用户画像长期记住」，写入 {@code claw_long_term} 的 MEMORY.md。
 * <p>
 * 与 {@link WriteMemoryTool}（结构化事实 → 分层记忆 facts 页）互补分流：
 * <ul>
 *   <li><b>身份声明 / 风格偏好</b> → write_long_term_memory（MEMORY.md，System 区固定注入）</li>
 *   <li><b>数据点 / 知识点</b> → write_memory（事实页，可检索、可能被预算挤掉）</li>
 * </ul>
 * 合并策略走 {@link LongTermMemoryWriter#appendMerged}：增量合并 + LLM 去重精炼，避免覆盖与膨胀。
 */
@Component
public class WriteLongTermMemoryTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(WriteLongTermMemoryTool.class);
    private static final String NAME = "write_long_term_memory";
    private static final String PARAMS_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"content\":{\"type\":\"string\",\"description\":\"要长期记住的用户画像内容（身份/姓名/职业、风格偏好、关注领域），会自动增量合并去重\"}"
            + "},"
            + "\"required\":[\"content\"]"
            + "}";

    private final LongTermMemoryWriter longTermMemoryWriter;

    public WriteLongTermMemoryTool(LongTermMemoryWriter longTermMemoryWriter) {
        this.longTermMemoryWriter = longTermMemoryWriter;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        return new ToolSpec(NAME,
                "写入长期记忆（用户画像/身份/风格偏好，存入 MEMORY.md）。"
                        + " 当用户明确声明「我叫/我是/我是做XX的/我的风格是/我喜欢XX/请记住我」等身份与偏好信息时使用，"
                        + " 内容会增量合并去重。注意：数据点/知识点应使用 write_memory 而非本工具。",
                PARAMS_SCHEMA);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        try {
            WriteLongTermMemoryParams params =
                    JsonUtils.fromJson(argumentsJson == null ? "{}" : argumentsJson, WriteLongTermMemoryParams.class);
            String content = params.getContent();
            if (content == null || content.trim().isEmpty()) {
                return ToolResult.error("参数 content 不能为空");
            }
            longTermMemoryWriter.appendMerged(AgentScopeContext.get(), content.trim());
            log.info("MEMORY.md 工具写入成功 (scope={})", AgentScopeContext.get() == null ? "default" : AgentScopeContext.get().keyPrefix());
            return ToolResult.success("已写入长期记忆（MEMORY.md），已增量合并去重");
        } catch (Exception e) {
            log.error("write_long_term_memory 工具执行失败", e);
            return ToolResult.error("写入长期记忆失败: " + e.getMessage());
        }
    }
}