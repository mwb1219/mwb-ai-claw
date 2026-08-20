package com.mwb.ai.claw.infrastructure.skill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.mwb.ai.claw.domain.skill.Skill;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

/**
 * SkillLoader 三级加载单测：运行目录（agent.skills-dir）→ 安装目录 skills（mwb.ai.claw.home）→ classpath。
 * 基础设施测试模块 classpath 无内置技能，兜底场景断言返回空列表（不抛异常）。
 */
public class SkillLoaderTest {

    private String origHomeProp;

    @Before
    public void setUp() {
        origHomeProp = System.getProperty("mwb.ai.claw.home");
        System.clearProperty("mwb.ai.claw.home");
    }

    @After
    public void tearDown() {
        if (origHomeProp == null) {
            System.clearProperty("mwb.ai.claw.home");
        } else {
            System.setProperty("mwb.ai.claw.home", origHomeProp);
        }
    }

    @Test
    public void testInstallDirOverridesClasspath() throws Exception {
        Path home = Files.createTempDirectory("mwb-home-");
        try {
            writeSkill(home.resolve("skills/my-skill"), "my-skill", "安装目录技能");
            System.setProperty("mwb.ai.claw.home", home.toString());

            AgentProperties props = new AgentProperties();
            List<Skill> skills = new SkillLoader(props).loadSkills();
            assertEquals("安装目录 skills 应接管技能集", 1, skills.size());
            assertEquals("my-skill", skills.get(0).getName());
            assertEquals("安装目录技能", skills.get(0).getDescription());
        } finally {
            deleteRecursively(home);
        }
    }

    @Test
    public void testSkillsDirOverridesInstallDir() throws Exception {
        Path work = Files.createTempDirectory("mwb-work-");
        Path home = Files.createTempDirectory("mwb-home-");
        try {
            writeSkill(work.resolve("skills/work-skill"), "work-skill", "运行目录技能");
            writeSkill(home.resolve("skills/home-skill"), "home-skill", "安装目录技能");
            System.setProperty("mwb.ai.claw.home", home.toString());

            AgentProperties props = new AgentProperties();
            props.setSkillsDir(work.resolve("skills").toString());
            List<Skill> skills = new SkillLoader(props).loadSkills();
            assertEquals("agent.skills-dir（运行目录）应优先于安装目录", 1, skills.size());
            assertEquals("work-skill", skills.get(0).getName());
        } finally {
            deleteRecursively(work);
            deleteRecursively(home);
        }
    }

    @Test
    public void testClasspathFallbackWhenNoExternalSkills() throws Exception {
        Path home = Files.createTempDirectory("mwb-home-");
        try {
            System.setProperty("mwb.ai.claw.home", home.toString());
            AgentProperties props = new AgentProperties(); // skillsDir 为空 → user.dir/skills（不存在）
            List<Skill> skills = new SkillLoader(props).loadSkills();
            assertNotNull("兜底不应为 null", skills);
            assertTrue("无外部技能且 classpath 无内置时返回空列表", skills.isEmpty());
        } finally {
            deleteRecursively(home);
        }
    }

    private void writeSkill(Path skillDir, String name, String description) throws Exception {
        Files.createDirectories(skillDir);
        String md = "---\nname: " + name + "\ndescription: " + description + "\n---\n正文指令\n";
        Files.write(skillDir.resolve("SKILL.md"), md.getBytes(StandardCharsets.UTF_8));
    }

    private void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignore) {
            }
        });
    }
}
