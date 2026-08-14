package com.mwb.ai.claw.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import com.mwb.ai.claw.infrastructure.util.ConfigFileLocator;

/**
 * .env 文件加载器：在 Spring 环境初始化阶段解析 .env 并注入为 PropertySource。
 * <p>
 * 查找策略复用 {@link ConfigFileLocator}：启动目录及其上级目录优先（兼容从任意
 * 工作目录 / IDE 启动），回退 classpath。
 * 通过 addLast 注入，保证系统环境变量（systemEnvironment）优先级更高，
 * 即 .env 仅作为开发环境默认值。
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String FILE_NAME = ".env";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> dotenv = loadDotenv();
        // 启动诊断：EnvironmentPostProcessor 阶段 logback 尚未初始化，直接输出到控制台
        System.out.println("[dotenv] 启动目录 user.dir = " + System.getProperty("user.dir"));
        if (dotenv.isEmpty()) {
            System.out.println("[dotenv] 未找到 .env，将使用系统环境变量或配置文件默认值");
        } else {
            System.out.println("[dotenv] 已加载 .env（" + dotenv.size() + " 个变量），"
                    + "DEFAULT_MODEL=" + dotenv.get("DEFAULT_MODEL")
                    + ", DEFAULT_API_KEY=" + (isEmpty(dotenv.get("DEFAULT_API_KEY"))
                    ? "【空】" : mask((String) dotenv.get("DEFAULT_API_KEY"))));
        }
        if (!dotenv.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource("dotenv", dotenv));
        }
    }

    private Map<String, Object> loadDotenv() {
        Map<String, Object> map = new LinkedHashMap<>();
        String content = ConfigFileLocator.readConfigFile(FILE_NAME);
        if (content == null || content.isEmpty()) {
            return map;
        }
        // 去除 UTF-8 BOM，避免首个 KEY 前混入 \uFEFF 导致解析失败
        if (content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        for (String line : content.split("\r?\n")) {
            parseLine(line, map);
        }
        return map;
    }

    private void parseLine(String line, Map<String, Object> map) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }
        int idx = trimmed.indexOf('=');
        if (idx <= 0) {
            return;
        }
        String key = trimmed.substring(0, idx).trim();
        String value = trimmed.substring(idx + 1).trim();
        // 去除首尾引号
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                value = value.substring(1, value.length() - 1);
            }
        }
        map.put(key, value);
    }

    private boolean isEmpty(Object value) {
        return value == null || value.toString().trim().isEmpty();
    }

    /** 脱敏展示：保留前 6 位，其余用 * 代替 */
    private String mask(String value) {
        if (value == null || value.length() <= 6) {
            return value;
        }
        return value.substring(0, 6) + "****";
    }
}
