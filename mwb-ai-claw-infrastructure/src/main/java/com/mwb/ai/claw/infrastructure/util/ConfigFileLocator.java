package com.mwb.ai.claw.infrastructure.util;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 配置文件查找工具：统一「运行目录及其上级目录优先，classpath 回退」的加载策略。
 * <p>
 * 从启动目录（user.dir）开始逐级向上查找目标文件，兼容从模块目录 / IDE / 脚本等
 * 任意工作目录启动的场景（配置文件通常位于项目根目录）。
 */
public final class ConfigFileLocator {

    private ConfigFileLocator() {
    }

    /**
     * 读取配置文件内容：1) 启动目录向上逐级查找；2) classpath 回退。
     *
     * @param fileName 文件名（如 .env、routing-agents.json、mcp-server.json）
     * @return 文件内容；均未找到返回 null
     */
    public static String readConfigFile(String fileName) {
        String external = readFromWorkDirUpwards(fileName);
        if (external != null) {
            return external;
        }
        return readFromClasspath(fileName);
    }

    private static String readFromWorkDirUpwards(String fileName) {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            File file = new File(dir, fileName);
            if (file.isFile()) {
                try {
                    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return null;
                }
            }
            dir = dir.getParentFile();
        }
        return null;
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
