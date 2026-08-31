package com.mwb.ai.claw.infrastructure.memory.storage.file;

import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 长期记忆文件版实现：基于 .agent/[namespace/]AGENT.md 和 .agent/[namespace/]MEMORY.md 持久化。
 * <p>
 * 目录默认取自 AgentProperties.memoryDir，若未配置则使用 ${user.dir}/.agent。
 * 多租户模式下按 namespace（tenant/user）分目录隔离；legacy 模式保持原扁平布局。
 */
public class FileBasedMemoryGateway implements LongTermMemoryGateway {

    private static final Logger log = LoggerFactory.getLogger(FileBasedSessionGateway.class);

    private final Path agentDir;

    public FileBasedMemoryGateway(AgentProperties properties) {
        String dir = properties.getMemoryDir();
        if (dir == null || dir.trim().isEmpty()) {
            dir = System.getProperty("user.dir") + "/.agent";
        }
        this.agentDir = Paths.get(dir);
        log.info("长期记忆目录: {}", agentDir.toAbsolutePath());
    }

    @PostConstruct
    public void init() {
        try {
            if (!Files.exists(agentDir)) {
                Files.createDirectories(agentDir);
            }
        } catch (IOException e) {
            log.warn("无法创建长期记忆目录: {}", e.getMessage());
        }
    }

    private Path file(AgentScope scope, String name) {
        String ns = scope != null ? scope.namespace() : null;
        Path base = agentDir;
        if (ns != null) {
            base = base.resolve(ns);
        }
        return base.resolve(name);
    }

    @Override
    public String loadAgentInstructions(AgentScope scope) {
        return readFile(file(scope, "AGENT.md"));
    }

    @Override
    public String loadMemory(AgentScope scope) {
        return readFile(file(scope, "MEMORY.md"));
    }

    @Override
    public void saveMemory(AgentScope scope, String content) {
        try {
            Path memoryMd = file(scope, "MEMORY.md");
            Files.createDirectories(memoryMd.getParent());
            Files.write(memoryMd, content.getBytes(StandardCharsets.UTF_8));
            log.info("长期记忆已保存: {} ({} bytes)", memoryMd, content.length());
        } catch (IOException e) {
            log.error("保存长期记忆失败: {}", e.getMessage(), e);
            throw new RuntimeException("保存长期记忆失败: " + e.getMessage(), e);
        }
    }

    private String readFile(Path file) {
        if (!Files.exists(file)) {
            return "";
        }
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            log.debug("读取文件: {} ({} bytes)", file.getFileName(), content.length());
            return content;
        } catch (IOException e) {
            log.warn("读取文件失败: {} -> {}", file, e.getMessage());
            return "";
        }
    }
}
