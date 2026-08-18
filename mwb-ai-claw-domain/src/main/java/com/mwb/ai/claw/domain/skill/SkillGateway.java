package com.mwb.ai.claw.domain.skill;

import java.util.List;

/**
 * 技能网关接口：技能的发现与按需加载（渐进式披露）。
 * <p>
 * domain 层定义，由 infrastructure 实现（目录扫描 + frontmatter 解析 + 启动校验）。
 */
public interface SkillGateway {

    /**
     * 技能清单（L1 发现层）：仅返回 name + description，不加载正文，
     * 供上下文组装器注入 system prompt 使用。
     */
    List<Skill> listSkills();

    /**
     * 按名取完整技能（L2 指令层）：含正文与资源目录；不存在返回 null。
     */
    Skill getSkill(String name);
}
