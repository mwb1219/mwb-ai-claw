package com.mwb.ai.claw.infrastructure.skill;

import com.mwb.ai.claw.domain.skill.Skill;
import com.mwb.ai.claw.domain.skill.SkillGateway;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 技能注册表实现：启动时加载技能并索引，向上下文组装器与 use_skill 工具提供访问。
 * <p>
 * listSkills 仅返回 name + description（L1 发现层）；getSkill 返回含正文的完整技能（L2 指令层）。
 * 技能总开关关闭时（agent.skills-enabled=false）由 ClawCoreAutoConfiguration 决定不创建本 Bean。
 */
public class SkillRegistryImpl implements SkillGateway {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistryImpl.class);

    private final SkillLoader skillLoader;
    private final AgentProperties agentProperties;

    private volatile Map<String, Skill> indexed;

    public SkillRegistryImpl(SkillLoader skillLoader, AgentProperties agentProperties) {
        this.skillLoader = skillLoader;
        this.agentProperties = agentProperties;
    }

    @PostConstruct
    public void init() {
        load();
    }

    private synchronized Map<String, Skill> load() {
        if (indexed == null) {
            List<Skill> skills = skillLoader.loadSkills();
            Map<String, Skill> map = new LinkedHashMap<>();
            for (Skill skill : skills) {
                map.put(skill.getName(), skill);
            }
            indexed = map;
            log.info("已加载技能 [{}]: {}（技能目录: {}）", map.size(),
                    map.keySet().stream().collect(Collectors.joining(", ")),
                    describeDir());
        }
        return indexed;
    }

    private String describeDir() {
        String dir = agentProperties.getSkillsDir();
        if (dir == null || dir.trim().isEmpty()) {
            return System.getProperty("user.dir") + "/skills";
        }
        return dir.trim();
    }

    @Override
    public List<Skill> listSkills() {
        load();
        List<Skill> view = new ArrayList<>();
        for (Skill skill : indexed.values()) {
            Skill light = new Skill();
            light.setName(skill.getName());
            light.setDescription(skill.getDescription());
            view.add(light);
        }
        return view;
    }

    @Override
    public Skill getSkill(String name) {
        load();
        return indexed.get(name);
    }
}
