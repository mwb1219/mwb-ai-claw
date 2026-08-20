package com.mwb.ai.claw.infrastructure.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * ConfigFileLocator 三级加载单测：运行目录 → 安装目录 config（mwb.ai.claw.home）→ classpath。
 * 注：user.dir 不可在单测中安全改写，本测试聚焦「安装目录 config 优先于 classpath」与回退行为。
 */
public class ConfigFileLocatorTest {

    private String origHomeProp;

    @Before
    public void setUp() {
        origHomeProp = System.getProperty("mwb.ai.claw.home");
        // 置空，避免受本地开发环境变量/属性影响
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
    public void testConfigDirOverridesClasspath() throws Exception {
        Path home = Files.createTempDirectory("mwb-home-");
        try {
            Path configDir = Files.createDirectories(home.resolve("config"));
            String external = "{\"from\": \"install-dir\"}";
            Files.write(configDir.resolve("agents.json"), external.getBytes(StandardCharsets.UTF_8));

            System.setProperty("mwb.ai.claw.home", home.toString());
            assertEquals("安装目录 config 应优先于 classpath", external,
                    ConfigFileLocator.readConfigFile("agents.json"));
        } finally {
            deleteRecursively(home);
        }
    }

    @Test
    public void testClasspathFallback() throws Exception {
        Path home = Files.createTempDirectory("mwb-home-");
        try {
            // config 目录为空或不存在 → 回退 classpath（test resources 内置 agents.json）
            System.setProperty("mwb.ai.claw.home", home.toString());
            String content = ConfigFileLocator.readConfigFile("agents.json");
            assertNotNull("应回退到 classpath 内置模板", content);
            assertTrue("应读到 classpath 内容", content.contains("classpath"));
        } finally {
            deleteRecursively(home);
        }
    }

    @Test
    public void testEnvFileFromHomeDirRoot() throws Exception {
        Path home = Files.createTempDirectory("mwb-home-");
        try {
            String envContent = "DEFAULT_MODEL=test-model\nDEFAULT_API_KEY=secret\n";
            Files.write(home.resolve(".env"), envContent.getBytes(StandardCharsets.UTF_8));

            System.setProperty("mwb.ai.claw.home", home.toString());
            assertEquals("安装目录根的 .env 应被加载（无需放入 config/ 子目录）",
                    envContent.trim(), ConfigFileLocator.readConfigFile(".env").trim());
        } finally {
            deleteRecursively(home);
        }
    }

    @Test
    public void testMissingFileReturnsNull() throws Exception {
        Path home = Files.createTempDirectory("mwb-home-");
        try {
            System.setProperty("mwb.ai.claw.home", home.toString());
            assertNull("不存在且 classpath 无此文件应返回 null",
                    ConfigFileLocator.readConfigFile("no-such-config-xyz.json"));
        } finally {
            deleteRecursively(home);
        }
    }

    private void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
            try {
                Files.deleteIfExists(p);
            } catch (Exception ignore) {
            }
        });
    }
}
