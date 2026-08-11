package com.mwb.ai.claw.infrastructure.tool;

import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 工具安全沙箱：提供路径限制、命令白/黑名单、输出截断等安全校验。
 * <p>
 * 所有内置工具执行前必须通过此类校验，防止 LLM 产生危险的工具调用。
 */
@Component
public class ToolSecurity {

    @Resource
    private AgentProperties properties;

    /**
     * 解析并校验文件路径是否在 workspace 范围内。
     *
     * @param inputPath 用户输入的路径（可能为相对路径）
     * @return 解析后的绝对路径
     * @throws SecurityException 路径越界时抛出
     */
    public Path resolveAndValidatePath(String inputPath) {
        AgentProperties.ToolSecurityConfig cfg = properties.getSecurity();
        Path path = Paths.get(inputPath).toAbsolutePath().normalize();

        if (cfg.isEnabled()) {
            String workspaceDir = cfg.getWorkspaceDir();
            if (workspaceDir != null && !workspaceDir.trim().isEmpty()) {
                Path workspace = Paths.get(workspaceDir).toAbsolutePath().normalize();
                if (!path.startsWith(workspace)) {
                    throw new SecurityException("路径越界：只允许操作 " + workspace + " 下的文件，当前路径: " + path);
                }
            }
        }
        return path;
    }

    /**
     * 校验 Shell 命令是否在白名单内且未命中黑名单。
     *
     * @param command 用户输入的命令
     * @throws SecurityException 命令被禁止时抛出
     */
    public void validateShellCommand(String command) {
        AgentProperties.ToolSecurityConfig cfg = properties.getSecurity();
        if (!cfg.isEnabled()) {
            return;
        }

        String trimmed = command.trim();

        // 1. 黑名单检查（优先级最高）
        List<String> blacklist = cfg.getShellBlacklist();
        if (blacklist != null) {
            for (String blocked : blacklist) {
                if (trimmed.toLowerCase().contains(blocked.toLowerCase())) {
                    throw new SecurityException("命令被安全策略禁止: 包含危险片段 '" + blocked + "'");
                }
            }
        }

        // 2. 白名单检查
        List<String> whitelist = cfg.getShellWhitelist();
        if (whitelist != null && !whitelist.isEmpty()) {
            // 提取命令的第一个 token（即程序名）
            String[] tokens = trimmed.split("\\s+");
            String cmdName = tokens[0];
            if (!whitelist.contains(cmdName)) {
                throw new SecurityException("命令不在白名单内: " + cmdName);
            }
        } else {
            throw new SecurityException("Shell 命令白名单为空，已禁止所有 Shell 执行");
        }
    }

    /**
     * 校验 HTTP 请求的目标 host 是否在允许列表内。
     *
     * @param urlString 目标 URL
     * @throws SecurityException host 被禁止时抛出
     */
    public void validateHttpUrl(String urlString) {
        AgentProperties.ToolSecurityConfig cfg = properties.getSecurity();
        if (!cfg.isEnabled()) {
            return;
        }

        List<String> allowedHosts = cfg.getHttpAllowedHosts();
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return; // 空列表表示全部允许
        }

        try {
            URI uri = new URI(urlString);
            String host = uri.getHost();
            if (host != null) {
                boolean allowed = false;
                for (String pattern : allowedHosts) {
                    if (host.equalsIgnoreCase(pattern) || host.endsWith("." + pattern)) {
                        allowed = true;
                        break;
                    }
                }
                if (!allowed) {
                    throw new SecurityException("HTTP 请求被禁止: host '" + host + "' 不在允许列表中");
                }
            }
        } catch (java.net.URISyntaxException e) {
            throw new SecurityException("URL 格式错误: " + urlString);
        }
    }

    /**
     * 截断输出至配置的最大长度。
     */
    public String truncateOutput(String output) {
        if (output == null) {
            return "";
        }
        int maxLen = properties.getSecurity().getMaxOutputLength();
        if (maxLen <= 0 || output.length() <= maxLen) {
            return output;
        }
        return output.substring(0, maxLen) + "\n... [输出已截断，共 " + output.length() + " 字符]";
    }

    /**
     * 获取工具超时时间（秒）。
     */
    public int getToolTimeoutSeconds() {
        return properties.getSecurity().getToolTimeoutSeconds();
    }
}
