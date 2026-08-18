package com.mwb.ai.claw.infrastructure.tool;

import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
     * <p>
     * 支持 shell 语义（管道 / 重定向 / 逻辑连接符）：整条命令先做黑名单子串检查，
     * 再按 shell 分隔符（; && || | 换行）拆分为命令段，逐段校验首 token 是否在白名单，
     * 防止通过 {@code ls; rm ...}、{@code cat x | python3 -c ...} 等链式写法绕过白名单。
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

        // 1. 黑名单检查（优先级最高）：作用于整条命令（子串匹配，可覆盖命令替换/管道等嵌套写法）
        List<String> blacklist = cfg.getShellBlacklist();
        if (blacklist != null) {
            for (String blocked : blacklist) {
                if (trimmed.toLowerCase().contains(blocked.toLowerCase())) {
                    throw new SecurityException("命令被安全策略禁止: 包含危险片段 '" + blocked + "'");
                }
            }
        }

        // 2. 白名单检查：按 shell 分隔符拆分后逐段校验首 token
        List<String> whitelist = cfg.getShellWhitelist();
        if (whitelist == null || whitelist.isEmpty()) {
            throw new SecurityException("Shell 命令白名单为空，已禁止所有 Shell 执行");
        }
        for (String segment : splitShellSegments(trimmed)) {
            String seg = segment.trim();
            if (seg.isEmpty()) {
                continue;
            }
            String[] tokens = seg.split("\\s+");
            String cmdName = tokens[0];
            if (!whitelist.contains(cmdName)) {
                throw new SecurityException("命令不在白名单内: " + cmdName + "（命令段: " + seg + "）");
            }
        }
    }

    /**
     * 按 shell 分隔符切分命令段（引号内不切分，支持 \ 转义），用于白名单逐段校验。
     * 分隔符：分号、管道（含 |&）、逻辑与（&&）、换行。
     */
    private List<String> splitShellSegments(String command) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0; // 0=未进入引号；' 或 " 表示引号内
        boolean escape = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escape) {
                current.append(c);
                escape = false;
                continue;
            }
            if (c == '\\' && quote != '\'') {
                current.append(c);
                escape = true;
                continue;
            }
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == ';' || c == '|' || c == '&' || c == '\n' || c == '\r') {
                segments.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        segments.add(current.toString());
        return segments;
    }

    /**
     * 当前 Shell 审批模式：auto | ask | read-only。
     */
    public String getShellApprovalMode() {
        return properties.getSecurity().getShellApprovalMode();
    }

    /**
     * 敏感信息脱敏：将命令输出中的常见密钥模式打码（sk-xxx、api_key=xxx、token: xxx、
     * password=xxx、Bearer xxx、AWS AKIA 等），避免明文密钥进入 LLM 上下文或终端日志。
     * <p>
     * 正则保守设计，尽量只命中明显的密钥形态，减少对普通文本的误伤。
     *
     * @param text 原始文本（命令输出）
     * @return 脱敏后的文本
     */
    public String maskSecrets(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = text;
        // sk-xxx（OpenAI / DeepSeek 等模型密钥）
        masked = masked.replaceAll("(?i)(sk-[A-Za-z0-9_-]{6})[A-Za-z0-9_-]+", "$1***");
        // 键值对：api_key=xxx / token: xxx / password=xxx 等
        masked = masked.replaceAll("(?i)((?:api[_-]?key|access[_-]?key|secret|token|password|passwd|auth)\\s*[=:]\\s*)([^\\s,;\"']+)", "$1***");
        // Authorization / Bearer 头
        masked = masked.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._-]+", "$1***");
        // AWS Access Key（AKIA + 16 位）
        masked = masked.replaceAll("(?i)(AKIA[0-9A-Z]{8})[0-9A-Z]{8}", "$1***");
        return masked;
    }

    /**
     * 判断命令是否命中审批规则（子串匹配，不区分大小写）。
     * <p>
     * 仅在 ask / read-only 模式下生效：ask 模式命中后需用户确认放行，
     * read-only 模式命中后直接拒绝执行。
     *
     * @param command 待执行命令全文
     * @return true = 需要审批 / 只读拦截
     */
    public boolean isApprovalRequired(String command) {
        String mode = getShellApprovalMode();
        if (mode == null) {
            return false;
        }
        String m = mode.trim().toLowerCase();
        if (!"ask".equals(m) && !"read-only".equals(m)) {
            return false; // auto 模式无需审批
        }
        List<String> patterns = properties.getSecurity().getShellApprovalPatterns();
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        String lower = command.toLowerCase();
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isEmpty() && lower.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
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
