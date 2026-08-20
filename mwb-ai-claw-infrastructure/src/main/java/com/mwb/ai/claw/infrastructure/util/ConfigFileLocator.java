package com.mwb.ai.claw.infrastructure.util;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 配置文件查找工具：统一「运行目录（user.dir）→ 用户配置目录 → classpath」的加载策略。
 * <p>
 * 1) 运行目录（user.dir）：单次运行临时覆盖，最高优先级；
 * 2) 用户配置目录：{@code $MWB_AI_CLAW_HOME/config/}（agents / orchestrations / mcp-server 模板）
 *    与 {@code $MWB_AI_CLAW_HOME} 安装目录根（.env 密钥配置，与启动器全局密钥约定一致），
 *    默认 {@code ~/.mwb-ai-claw}；由启动器以系统属性 {@code mwb.ai.claw.home} 注入，
 *    用户在此修改配置后重启即生效；
 * 3) classpath 内置模板：兜底默认。
 * <p>
 * 各级目录仅查找目标文件（不向上级目录搜索），命中即返回、不再读取低优先级来源。
 */
public final class ConfigFileLocator {

    private ConfigFileLocator() {
    }

    /**
     * 读取配置文件内容：1) 运行目录；2) 用户配置目录（config/ 子目录 → 安装目录根）；3) classpath 回退。
     *
     * @param fileName 文件名（如 .env、agents.json、orchestrations.json、mcp-server.json）
     * @return 文件内容；均未找到返回 null
     */
    public static String readConfigFile(String fileName) {
        String fromUserDir = readFromUserDir(fileName);
        if (fromUserDir != null) {
            return fromUserDir;
        }
        String fromConfigDir = readFromConfigDir(fileName);
        if (fromConfigDir != null) {
            return fromConfigDir;
        }
        return readFromClasspath(fileName);
    }

    private static String readFromUserDir(String fileName) {
        return readFromDir(new File(System.getProperty("user.dir")), fileName);
    }

    private static String readFromConfigDir(String fileName) {
        // config/ 子目录（agents.json / orchestrations.json / mcp-server.json 模板）
        String content = readFromDir(configDir(), fileName);
        if (content != null) {
            return content;
        }
        // 安装目录根（.env 密钥配置，与启动器读取的 $MWB_AI_CLAW_HOME/.env 约定一致）
        return readFromDir(homeDir(), fileName);
    }

    private static String readFromDir(File dir, String fileName) {
        File file = new File(dir.getAbsoluteFile(), fileName);
        if (file.isFile()) {
            try {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 安装目录根：系统属性 {@code mwb.ai.claw.home}（启动器注入）→ 环境变量
     * {@code MWB_AI_CLAW_HOME} → 默认 {@code ~/.mwb-ai-claw}。
     */
    public static File homeDir() {
        String home = System.getProperty("mwb.ai.claw.home");
        if (home == null || home.trim().isEmpty()) {
            home = System.getenv("MWB_AI_CLAW_HOME");
        }
        if (home == null || home.trim().isEmpty()) {
            home = System.getProperty("user.home") + File.separator + ".mwb-ai-claw";
        }
        return new File(home);
    }

    private static File configDir() {
        return new File(homeDir(), "config");
    }

    private static String readFromClasspath(String fileName) {
        try {
            ClassPathResource resource = new ClassPathResource(fileName);
            if (resource.exists()) {
                try (InputStream in = resource.getInputStream()) {
                    return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
