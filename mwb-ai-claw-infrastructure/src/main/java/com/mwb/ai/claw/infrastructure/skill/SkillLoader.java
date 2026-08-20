package com.mwb.ai.claw.infrastructure.skill;

import com.mwb.ai.claw.domain.skill.Skill;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.util.ConfigFileLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能加载器：扫描技能根目录并解析 SKILL.md（YAML frontmatter + 指令正文）。
 * <p>
 * 加载策略与配置文件一致（三级）：运行目录 <skills-dir> 优先 → 安装目录
 * {@code $MWB_AI_CLAW_HOME/skills/} → classpath skills/ 内置模板兜底；
 * 外部目录命中（非空）即用，不再读取低优先级来源（用户自定义技能集完全接管）。
 * frontmatter 解析复用 Spring Boot 传递依赖 SnakeYAML。
 * 启动校验：name / description 缺失、name 与目录名不一致、name 重复 → 抛异常。
 */
@Component
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);

    private static final String SKILL_FILE = "SKILL.md";
    private static final String FRONTMATTER_DELIM = "---";
    private static final String CLASS_PATH_PREFIX = "/skills/";

    private final AgentProperties agentProperties;

    public SkillLoader(AgentProperties agentProperties) {
        this.agentProperties = agentProperties;
    }

    /**
     * 加载全部技能（运行目录 → 安装目录 → classpath 兜底），并执行启动校验。
     *
     * @return 技能列表（可能为空，不会为 null）
     */
    public List<Skill> loadSkills() {
        // 1. 运行目录（agent.skills-dir 显式指定或默认 user.dir/skills）
        List<Skill> skills = loadExternal(userSkillsDir());
        // 2. 安装目录 skills（install 时随包复制，用户可直接增删）
        if (skills == null) {
            skills = loadExternal(installSkillsDir());
        }
        // 3. classpath 内置模板兜底
        if (skills == null) {
            skills = loadClasspath();
        }
        if (skills == null) {
            skills = new ArrayList<>();
        }
        validate(skills);
        return skills;
    }

    /** 运行目录技能根：agent.skills-dir 显式指定优先，默认 user.dir/skills */
    private String userSkillsDir() {
        String dir = agentProperties.getSkillsDir();
        return dir == null || dir.trim().isEmpty()
                ? System.getProperty("user.dir") + File.separator + "skills"
                : dir.trim();
    }

    /** 安装目录技能根：{@code $MWB_AI_CLAW_HOME/skills/}（与 ConfigFileLocator 同一安装目录解析） */
    private String installSkillsDir() {
        return new File(ConfigFileLocator.homeDir(), "skills").getAbsolutePath();
    }

    /** 加载指定外部目录技能；目录不存在或空返回 null */
    private List<Skill> loadExternal(String dir) {
        File root = new File(dir);
        if (!root.isDirectory()) {
            return null;
        }
        File[] skillDirs = root.listFiles(File::isDirectory);
        if (skillDirs == null || skillDirs.length == 0) {
            return null;
        }
        List<Skill> skills = new ArrayList<>();
        for (File skillDir : skillDirs) {
            File skillFile = new File(skillDir, SKILL_FILE);
            if (!skillFile.isFile()) {
                continue;
            }
            try {
                String content = new String(Files.readAllBytes(skillFile.toPath()), StandardCharsets.UTF_8);
                skills.add(parse(content, skillDir.getName(), skillDir.getAbsolutePath()));
            } catch (Exception e) {
                throw new IllegalArgumentException("技能目录解析失败: " + skillDir.getAbsolutePath(), e);
            }
        }
        return skills.isEmpty() ? null : skills;
    }

    /** 加载 classpath 内置技能（start 模块 resources/skills/）；无则返回 null */
    private List<Skill> loadClasspath() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:skills/*/" + SKILL_FILE);
            if (resources == null || resources.length == 0) {
                return null;
            }
            List<Skill> skills = new ArrayList<>();
            for (Resource resource : resources) {
                String url = resource.getURL().toString();
                String dirName = skillNameFromUrl(url);
                try (InputStream in = resource.getInputStream()) {
                    String content = new String(
                            org.springframework.util.StreamUtils.copyToByteArray(in), StandardCharsets.UTF_8);
                    // classpath 内置技能无文件系统路径，baseDir 为空（$SKILL_DIR 不可用）
                    skills.add(parse(content, dirName, ""));
                }
            }
            return skills.isEmpty() ? null : skills;
        } catch (Exception e) {
            throw new IllegalArgumentException("classpath 技能扫描失败", e);
        }
    }

    /** 从资源 URL 提取技能目录名（如 jar:...!/skills/example-skill/SKILL.md → example-skill） */
    private String skillNameFromUrl(String url) {
        int idx = url.indexOf(CLASS_PATH_PREFIX);
        if (idx < 0) {
            throw new IllegalArgumentException("无法识别的技能资源路径: " + url);
        }
        String rest = url.substring(idx + CLASS_PATH_PREFIX.length());
        int end = rest.indexOf('/');
        return end > 0 ? rest.substring(0, end) : rest;
    }

    /** 解析 SKILL.md：提取 frontmatter（name / description）与正文 */
    private Skill parse(String fileContent, String dirName, String baseDir) {
        String frontmatter = null;
        String body = fileContent;
        if (fileContent.startsWith(FRONTMATTER_DELIM)) {
            int end = fileContent.indexOf("\n" + FRONTMATTER_DELIM, 3);
            if (end > 0) {
                frontmatter = fileContent.substring(3, end);
                body = fileContent.substring(end + 4);
            }
        }
        Map<String, Object> meta = Collections.emptyMap();
        if (frontmatter != null && !frontmatter.trim().isEmpty()) {
            Object parsed = new Yaml().load(frontmatter);
            if (parsed instanceof Map) {
                meta = (Map<String, Object>) parsed;
            }
        }
        Skill skill = new Skill();
        skill.setName(toStringValue(meta.get("name")));
        skill.setDescription(toStringValue(meta.get("description")));
        skill.setContent(body == null ? "" : body.trim());
        skill.setBaseDir(baseDir == null ? "" : baseDir);

        if (skill.getName() == null || skill.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("技能 " + dirName + " 缺少 frontmatter 必需字段 name");
        }
        if (skill.getDescription() == null || skill.getDescription().trim().isEmpty()) {
            throw new IllegalArgumentException("技能 " + skill.getName() + " 缺少 frontmatter 必需字段 description");
        }
        if (!skill.getName().trim().equals(dirName)) {
            throw new IllegalArgumentException("技能 name 与目录名不一致: name=" + skill.getName()
                    + "，目录=" + dirName + "（name 必须与目录名一致）");
        }
        skill.setName(skill.getName().trim());
        skill.setDescription(skill.getDescription().trim());
        return skill;
    }

    private String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 启动校验：name 重复 */
    private void validate(List<Skill> skills) {
        Set<String> names = new HashSet<>();
        for (Skill skill : skills) {
            if (!names.add(skill.getName())) {
                throw new IllegalArgumentException("技能 name 重复: " + skill.getName());
            }
        }
    }
}
