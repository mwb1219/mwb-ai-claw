package com.mwb.ai.claw.infrastructure.util;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 配置文件查找工具：统一「运行目录（user.dir）优先，classpath 回退」的加载策略。
 * <p>
 * 仅读取 user.dir 目录下的目标文件（不向上级目录查找），外部命中即返回、不再读取
 * 内置模板；未命中时回退 classpath 默认模板。
 */
public final class ConfigFileLocator {

    private ConfigFileLocator() {
    }

    /**
     * 读取配置文件内容：1) user.dir 目录下同名文件；2) classpath 回退。
     *
     * @param fileName 文件名（如 .env、agents.json、orchestrations.json、mcp-server.json）
     * @return 文件内容；均未找到返回 null
     */
    public static String readConfigFile(String fileName) {
        String external = readFromUserDir(fileName);
        if (external != null) {
            return external;
        }
        return readFromClasspath(fileName);
    }

    private static String readFromUserDir(String fileName) {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        File file = new File(dir, fileName);
        if (file.isFile()) {
            try {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
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
