package com.mwb.ai.claw.domain.skill;

import lombok.Data;

/**
 * 技能值对象：对应一个技能目录（skills/&lt;name&gt;/）。
 * <p>
 * 遵循 Agent Skills 开放标准：name（kebab-case，与目录名一致）+ description（触发信号）+ 指令正文。
 * 渐进式披露（Progressive Disclosure）：
 * <ul>
 *   <li>L1 发现层：{@code name + description} 常驻 system prompt，供 LLM 判断何时触发；</li>
 *   <li>L2 指令层：{@link #content}（SKILL.md 正文）按需加载（use_skill 工具）；</li>
 *   <li>L3 资源层：{@link #baseDir} 指向技能目录，资源文件按 {@code $SKILL_DIR} 路径按需读取。</li>
 * </ul>
 */
@Data
public class Skill {

    /** 技能名（kebab-case，与目录名一致） */
    private String name;

    /** 技能描述（what + when + 触发词），L1 发现层的路由信号 */
    private String description;

    /** SKILL.md 正文（L2，按需加载） */
    private String content;

    /** 技能目录绝对路径（L3 资源访问根，$SKILL_DIR 来源；classpath 内置技能为空） */
    private String baseDir;
}
