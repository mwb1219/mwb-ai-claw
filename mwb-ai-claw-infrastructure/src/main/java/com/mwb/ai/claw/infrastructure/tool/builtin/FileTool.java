package com.mwb.ai.claw.infrastructure.tool.builtin;

import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.infrastructure.tool.ToolSecurity;
import com.mwb.ai.claw.infrastructure.tool.builtin.dto.FileParams;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件工具：支持读取文件、写入文件、列出目录等操作。
 * 所有路径操作受安全沙箱限制，只能在 workspace 目录内操作。
 */
@Component
public class FileTool implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(FileTool.class);

    @Resource
    private ToolSecurity toolSecurity;

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public ToolSpec getSpec() {
        String params = "{\n"
                + "  \"type\": \"object\",\n"
                + "  \"properties\": {\n"
                + "    \"action\": {\n"
                + "      \"type\": \"string\",\n"
                + "      \"description\": \"操作类型: read(读取文件) / write(写入文件) / list(列出目录)\",\n"
                + "      \"enum\": [\"read\", \"write\", \"list\"]\n"
                + "    },\n"
                + "    \"path\": {\n"
                + "      \"type\": \"string\",\n"
                + "      \"description\": \"文件或目录路径\"\n"
                + "    },\n"
                + "    \"content\": {\n"
                + "      \"type\": \"string\",\n"
                + "      \"description\": \"write 操作时的文件内容\"\n"
                + "    }\n"
                + "  },\n"
                + "  \"required\": [\"action\", \"path\"]\n"
                + "}";
        return new ToolSpec("file", "读取/写入文件或列出目录内容", params);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        try {
            FileParams params = JsonUtils.fromJson(argumentsJson, FileParams.class);
            String action = params.getAction();
            String path = params.getPath();

            if (action == null || path == null) {
                return ToolResult.error("缺少必填参数: action 和 path");
            }

            switch (action.toLowerCase()) {
                case "read":
                    return doRead(path);
                case "write":
                    return doWrite(path, params.getContent());
                case "list":
                    return doList(path);
                default:
                    return ToolResult.error("不支持的操作类型: " + action + "，支持: read, write, list");
            }
        } catch (SecurityException e) {
            log.warn("文件安全校验失败: {}", e.getMessage());
            return ToolResult.error("安全拦截: " + e.getMessage());
        } catch (Exception e) {
            log.error("文件操作失败", e);
            return ToolResult.error("文件操作失败: " + e.getMessage());
        }
    }

    private ToolResult doRead(String inputPath) throws IOException {
        Path path = toolSecurity.resolveAndValidatePath(inputPath);
        if (!Files.exists(path)) {
            return ToolResult.error("文件不存在: " + path);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.error("路径是目录而非文件: " + path + "，请使用 list 操作");
        }
        String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        String output = toolSecurity.truncateOutput(content);
        return ToolResult.success("文件: " + path + "\n" + output);
    }

    private ToolResult doWrite(String inputPath, String content) throws IOException {
        Path path = toolSecurity.resolveAndValidatePath(inputPath);
        if (content == null) {
            return ToolResult.error("写入操作需要提供 content 参数");
        }
        // 确保父目录存在
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        return ToolResult.success("已写入文件: " + path + " (" + content.length() + " 字符)");
    }

    private ToolResult doList(String inputPath) throws IOException {
        Path path = toolSecurity.resolveAndValidatePath(inputPath);
        if (!Files.exists(path)) {
            return ToolResult.error("目录不存在: " + path);
        }
        if (!Files.isDirectory(path)) {
            return ToolResult.error("路径是文件而非目录: " + path + "，请使用 read 操作");
        }

        StringBuilder sb = new StringBuilder();
        try (java.util.stream.Stream<Path> stream = Files.list(path)) {
            stream.forEach(p -> {
                try {
                    String name = p.getFileName().toString();
                    boolean isDir = Files.isDirectory(p);
                    long size = Files.size(p);
                    sb.append(String.format("%s %-40s %d bytes%n",
                            isDir ? "[DIR]" : "[FILE]", name, size));
                } catch (IOException e) {
                    sb.append(p.getFileName()).append(" (error)").append("\n");
                }
            });
        }
        String output = toolSecurity.truncateOutput(sb.toString());
        return ToolResult.success("目录: " + path + "\n" + output);
    }
}
