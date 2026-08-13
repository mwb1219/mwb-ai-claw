package com.mwb.ai.claw.infrastructure.tool.mcp;

import com.mwb.ai.claw.domain.tool.McpServerConfig;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 配置加载器：从 mcp-server.json 读取配置。
 * <p>
 * 读取优先级：
 * 1. 运行目录下的外部 mcp-server.json（便于运行时覆盖，无需重新打包）
 * 2. classpath 下的 mcp-server.json（打包进 jar 的默认配置）
 * <p>
 * 文件格式与 Cursor / Claude 的 mcp.json 保持一致（顶层 mcpServers map）。
 */
@Component
public class McpServerConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(McpServerConfigLoader.class);
    private static final String FILE_NAME = "mcp-server.json";

    /**
     * 加载并转换为领域配置对象列表。
     */
    public List<McpServerConfig> load() {
        String json = readConfig();
        if (json == null || json.trim().isEmpty()) {
            log.info("未找到 {}，跳过 MCP Server 加载", FILE_NAME);
            return new ArrayList<>();
        }
        McpServersFile file = JsonUtils.fromJson(json, McpServersFile.class);
        return toServerConfigs(file);
    }

    private String readConfig() {
        // 1. 优先读取运行目录下的外部文件
        File external = new File(FILE_NAME);
        if (external.exists()) {
            try {
                return new String(Files.readAllBytes(external.toPath()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("读取外部 {} 失败: {}", FILE_NAME, e.getMessage());
            }
        }
        // 2. 回退到 classpath
        try {
            ClassPathResource resource = new ClassPathResource(FILE_NAME);
            if (resource.exists()) {
                try (InputStream in = resource.getInputStream()) {
                    return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            log.warn("读取 classpath {} 失败: {}", FILE_NAME, e.getMessage());
        }
        return null;
    }

    private List<McpServerConfig> toServerConfigs(McpServersFile file) {
        List<McpServerConfig> configs = new ArrayList<>();
        if (file == null || file.getMcpServers() == null) {
            return configs;
        }
        for (Map.Entry<String, McpServersFile.McpServerEntry> e : file.getMcpServers().entrySet()) {
            McpServersFile.McpServerEntry entry = e.getValue();
            if (entry == null) {
                continue;
            }
            McpServerConfig cfg = new McpServerConfig();
            cfg.setName(e.getKey());
            cfg.setTransport(resolveTransport(entry));
            cfg.setCommand(entry.getCommand());
            cfg.setArgs(entry.getArgs() != null ? entry.getArgs() : new ArrayList<>());
            cfg.setEnv(entry.getEnv() != null ? entry.getEnv() : new java.util.HashMap<>());
            cfg.setUrl(entry.getUrl());
            cfg.setHeaders(entry.getHeaders() != null ? entry.getHeaders() : new java.util.HashMap<>());
            cfg.setEnabled(entry.getEnabled() == null || entry.getEnabled());
            configs.add(cfg);
        }
        return configs;
    }

    /**
     * 推断传输类型：显式 transport 优先，其次按 command（stdio）/ url（sse）推断。
     */
    private String resolveTransport(McpServersFile.McpServerEntry entry) {
        if (entry.getTransport() != null && !entry.getTransport().trim().isEmpty()) {
            return entry.getTransport().toLowerCase();
        }
        if (entry.getCommand() != null && !entry.getCommand().trim().isEmpty()) {
            return "stdio";
        }
        if (entry.getUrl() != null && !entry.getUrl().trim().isEmpty()) {
            return "sse";
        }
        return "stdio";
    }
}
