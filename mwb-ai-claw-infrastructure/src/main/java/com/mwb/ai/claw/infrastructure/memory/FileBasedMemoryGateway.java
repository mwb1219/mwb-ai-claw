package com.mwb.ai.claw.infrastructure.memory;

import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 长期记忆文件版实现：基于 .agent/AGENT.md 和 .agent/MEMORY.md 持久化。
 * <p>
 * 目录默认取自 AgentProperties.memoryDir，若未配置则使用 ${user.dir}/.agent。
 */
@Component
public class FileBasedMemoryGateway implements LongTermMemoryGateway {

    private static final Logger log = LoggerFactory.getLogger(FileBasedMemoryGateway.class);

    private final Path agentMd;
    private final Path memoryMd;

    public FileBasedMemoryGateway(AgentProperties properties) {
        String dir = properties.getMemoryDir();
        if (dir == null || dir.trim().isEmpty()) {
            dir = System.getProperty("user.dir") + "/.agent";
        }
        Path agentDir = Paths.get(dir);
        this.agentMd = agentDir.resolve("AGENT.md");
        this.memoryMd = agentDir.resolve("MEMORY.md");
        log.info("长期记忆目录: {}", agentDir.toAbsolutePath());
    }

    @PostConstruct
    public void init() {
        try {
            Path parent = agentMd.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            log.warn("无法创建长期记忆目录: {}", e.getMessage());
        }
    }

    @Override
    public String loadAgentInstructions() {
        return readFile(agentMd);
    }

    @Override
    public String loadMemory() {
        return readFile(memoryMd);
    }

    @Override
    public void saveMemory(String content) {
        try {
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
