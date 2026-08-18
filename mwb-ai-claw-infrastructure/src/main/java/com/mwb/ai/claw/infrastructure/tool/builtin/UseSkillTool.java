package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.mwb.ai.claw.domain.skill.Skill;
import com.mwb.ai.claw.domain.skill.SkillGateway;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 技能加载工具（渐进式披露 L2 指令层）：按名加载 SKILL.md 完整指令注入上下文。
 * <p>
 * 注册为全局工具（global=true，对齐 MCP 工具），对所有 Agent 可见，无需在 agents.json 声明。
 * 技能清单（name + description）由上下文组装器注入 system prompt（L1 发现层），
 * LLM 判定任务匹配某技能描述时调用本工具加载详细指令。
 * {@code $SKILL_DIR} 占位符替换为技能目录绝对路径，供 L3 资源按需读取。
 */
@Component
@ConditionalOnProperty(name = "agent.skills-enabled", havingValue = "true", matchIfMissing = true)
public class UseSkillTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(UseSkillTool.class);
    private static final String NAME = "use_skill";
    private static final String PARAMS_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"skill\":{\"type\":\"string\",\"description\":\"技能名称（见 system prompt「可用技能」清单）\"}"
            + "},"
            + "\"required\":[\"skill\"]"
            + "}";

    private final SkillGateway skillGateway;

    public UseSkillTool(SkillGateway skillGateway) {
        this.skillGateway = skillGateway;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        ToolSpec spec = new ToolSpec(NAME,
                "按需加载技能（Skill）的完整指令。技能清单见 system prompt「可用技能」区块；"
                        + "当任务与某技能描述匹配时，调用此工具加载该技能详细指令并按其执行。",
                PARAMS_SCHEMA);
        spec.setGlobal(true);
        return spec;
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        try {
            String skillName = JsonUtils.readTree(argumentsJson).path("skill").asText(null);
            if (skillName == null || skillName.trim().isEmpty()) {
                return ToolResult.error("use_skill 缺少必填参数 skill");
            }
            Skill skill = skillGateway.getSkill(skillName.trim());
            if (skill == null) {
                List<Skill> all = skillGateway.listSkills();
                String available = all.stream().map(Skill::getName).collect(Collectors.joining(", "));
                return ToolResult.error("技能不存在: " + skillName
                        + (available.isEmpty() ? "（当前没有可用技能）" : "（可用技能: " + available + "）"));
            }
            String content = skill.getContent();
            if (content == null || content.trim().isEmpty()) {
                return ToolResult.success("技能「" + skill.getName() + "」没有更多指令内容，直接按描述执行即可。");
            }
            String resolved = skill.getBaseDir() != null && !skill.getBaseDir().isEmpty()
                    ? content.replace("$SKILL_DIR", skill.getBaseDir())
                    : content;
            return ToolResult.success("技能「" + skill.getName() + "」指令如下：\n\n" + resolved);
        } catch (Exception e) {
            log.error("加载技能失败", e);
            return ToolResult.error("加载技能失败: " + e.getMessage());
        }
    }
}
