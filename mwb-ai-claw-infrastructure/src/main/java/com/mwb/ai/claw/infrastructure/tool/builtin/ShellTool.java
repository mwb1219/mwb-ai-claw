package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.infrastructure.tool.ToolSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Shell 工具：在受限环境中执行 Shell 命令。
 * 受安全沙箱保护：命令必须通过白名单校验，执行有超时限制，输出有长度截断。
 */
@Component
public class ShellTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ShellTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Resource
    private ToolSecurity toolSecurity;

    @Override
    public String getName() {
        return "shell";
    }

    @Override
    public ToolSpec getSpec() {
        String params = "{\n"
                + "  \"type\": \"object\",\n"
                + "  \"properties\": {\n"
                + "    \"command\": {\n"
                + "      \"type\": \"string\",\n"
                + "      \"description\": \"要执行的 Shell 命令\"\n"
                + "    },\n"
                + "    \"workingDir\": {\n"
                + "      \"type\": \"string\",\n"
                + "      \"description\": \"可选的工作目录\"\n"
                + "    }\n"
                + "  },\n"
                + "  \"required\": [\"command\"]\n"
                + "}";
        return new ToolSpec("shell", "在受限环境中执行 Shell 命令（受白名单和超时保护）", params);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        try {
            JsonNode args = mapper.readTree(argumentsJson);
            String command = getText(args, "command");
            String workingDir = getText(args, "workingDir");

            if (command == null || command.trim().isEmpty()) {
                return ToolResult.error("命令不能为空");
            }

            // 安全校验
            toolSecurity.validateShellCommand(command);

            // 解析命令（支持带引号的参数）
            ProcessBuilder pb;
            String[] cmdParts = parseCommand(command);
            pb = new ProcessBuilder(cmdParts);
            pb.redirectErrorStream(true);

            // 设置工作目录
            if (workingDir != null && !workingDir.isEmpty()) {
                java.nio.file.Path dir = toolSecurity.resolveAndValidatePath(workingDir);
                pb.directory(dir.toFile());
            }

            int timeoutSeconds = toolSecurity.getToolTimeoutSeconds();
            StringBuilder output = new StringBuilder();

            try {
                Process process = pb.start();

                // 异步读取输出，避免死锁
                Thread readerThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append("\n");
                        }
                    } catch (Exception e) {
                        log.warn("读取进程输出异常", e);
                    }
                });
                readerThread.setDaemon(true);
                readerThread.start();

                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return ToolResult.error("命令执行超时（" + timeoutSeconds + " 秒）: " + command);
                }

                readerThread.join(2000);

                int exitCode = process.exitValue();
                String result = output.toString();
                String truncated = toolSecurity.truncateOutput(result);

                if (exitCode == 0) {
                    return ToolResult.success(truncated);
                } else {
                    return ToolResult.error("命令执行失败 (exit=" + exitCode + "):\n" + truncated);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("命令执行被中断");
            }
        } catch (SecurityException e) {
            log.warn("Shell 安全校验失败: {}", e.getMessage());
            return ToolResult.error("安全拦截: " + e.getMessage());
        } catch (Exception e) {
            log.error("Shell 执行失败", e);
            return ToolResult.error("Shell 执行失败: " + e.getMessage());
        }
    }

    /**
     * 解析命令字符串为数组，支持双引号包裹的参数。
     */
    private String[] parseCommand(String command) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens.toArray(new String[0]);
    }

    private String getText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
