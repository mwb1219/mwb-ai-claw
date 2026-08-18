package com.mwb.ai.claw.shell;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 自定义斜杠命令加载器：扫描命令目录加载 *.md 定义（启动时执行一次）。
 * <p>
 * 搜索目录（优先级从高到低，全部扫描并去重）：
 * <ol>
 *   <li>{user.home}/.claw/commands —— 用户级全局命令</li>
 *   <li>{user.dir}/.claw/commands —— 项目级命令</li>
 *   <li>{user.dir}/commands —— 项目级命令（兼容 OpenCode 风格）</li>
 * </ol>
 * 文件格式（frontmatter + 模板正文，对齐 OpenCode custom commands）：
 * <pre>
 * ---
 * name: review
 * description: 以资深架构师视角做代码评审
 * ---
 * 请以资深架构师视角评审以下变更，给出问题清单与改进建议：{args}
 * </pre>
 */
public class CustomCommandLoader {

    private static final Logger log = LoggerFactory.getLogger(CustomCommandLoader.class);

    private static final String DIR_NAME = ".claw/commands";
    private static final String FRONTMATTER_DELIM = "---";

    /**
     * 加载全部自定义命令（可能为空，不会为 null）。
     */
    public List<CustomCommand> load() {
        List<CustomCommand> commands = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        dirs.add(new File(System.getProperty("user.home"), DIR_NAME));
        dirs.add(new File(System.getProperty("user.dir"), DIR_NAME));
        dirs.add(new File(System.getProperty("user.dir"), "commands"));

        for (File dir : dirs) {
            if (!dir.isDirectory()) {
                continue;
            }
            File[] files = dir.listFiles((d, n) -> n.endsWith(".md"));
            if (files == null) {
                continue;
            }
            Arrays.sort(files);
            for (File f : files) {
                try {
                    CustomCommand cc = parse(f);
                    if (cc != null) {
                        commands.add(cc);
                    }
                } catch (Exception e) {
                    log.warn("加载自定义命令失败: {}", f.getAbsolutePath(), e);
                }
            }
        }
        if (log.isDebugEnabled() && !commands.isEmpty()) {
            log.debug("已加载自定义命令 {} 个: {}", commands.size(),
                    commands.stream().map(CustomCommand::getName).collect(java.util.stream.Collectors.joining(", ")));
        }
        return commands;
    }

    /** 解析单个命令文件：frontmatter（name/description）+ 模板正文 */
    private CustomCommand parse(File f) throws IOException {
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        if (lines.isEmpty() || !FRONTMATTER_DELIM.equals(lines.get(0).trim())) {
            return null;
        }
        int end = -1;
        Map<String, String> fm = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (FRONTMATTER_DELIM.equals(line)) {
                end = i;
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                fm.put(line.substring(0, colon).trim().toLowerCase(),
                        line.substring(colon + 1).trim());
            }
        }
        if (end < 0) {
            return null;
        }
        String name = fm.get("name");
        if (name == null || name.isEmpty()) {
            log.warn("自定义命令缺少 name: {}", f.getAbsolutePath());
            return null;
        }
        StringBuilder body = new StringBuilder();
        for (int i = end + 1; i < lines.size(); i++) {
            body.append(lines.get(i)).append('\n');
        }
        CustomCommand cc = new CustomCommand();
        cc.setName(name.toLowerCase());
        cc.setDescription(fm.getOrDefault("description", ""));
        cc.setTemplate(body.toString().trim());
        return cc;
    }
}
