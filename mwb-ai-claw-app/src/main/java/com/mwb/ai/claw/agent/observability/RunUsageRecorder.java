package com.mwb.ai.claw.agent.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

import lombok.Data;

/**
 * 每次运行用量记录器：一次 Agent 执行结束后追加一条 JSONL 运行摘要（结构化日志的可查数据源）。
 * <p>
 * 输出目录默认 {memory-dir}/runs（memory-dir 未配置时为 {user.dir}/.agent/runs），
 * 可按 {@code agent.observability.run-usage-dir} 覆盖；{@code agent.observability.run-usage-log=false} 关闭。
 */
@Component
public class RunUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(RunUsageRecorder.class);

    private final AgentProperties properties;

    public RunUsageRecorder(AgentProperties properties) {
        this.properties = properties;
    }

    /** 一次 Agent 运行的用量摘要 */
    @Data
    public static class RunUsage {
        private String sessionId;
        private String agentId;
        private String orchestration;
        private String model;
        private long durationMs;
        private boolean success;
        private int steps;
        private String errorCode;
    }

    /**
     * 记录一次运行摘要。开关关闭或 IO 失败时静默降级，不影响主链路。
     */
    public void record(RunUsage usage) {
        if (usage == null || !properties.getObservability().isRunUsageLog()) {
            return;
        }
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ts", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            entry.put("sessionId", usage.getSessionId());
            entry.put("agentId", usage.getAgentId());
            entry.put("orchestration", usage.getOrchestration());
            entry.put("model", usage.getModel());
            entry.put("durationMs", usage.getDurationMs());
            entry.put("success", usage.isSuccess());
            entry.put("steps", usage.getSteps());
            entry.put("errorCode", usage.getErrorCode());

            Path dir = usageDir();
            Files.createDirectories(dir);
            Path file = dir.resolve(LocalDate.now() + ".jsonl");
            Files.write(file, (JsonUtils.toJson(entry) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("记录运行用量失败: {}", e.getMessage());
        }
    }

    private Path usageDir() {
        String dir = properties.getObservability().getRunUsageDir();
        if (dir == null || dir.trim().isEmpty()) {
            String memoryDir = properties.getMemoryDir();
            dir = (memoryDir == null || memoryDir.trim().isEmpty())
                    ? Paths.get(System.getProperty("user.dir"), ".agent", "runs").toString()
                    : Paths.get(memoryDir, "runs").toString();
        }
        return Paths.get(dir);
    }
}
