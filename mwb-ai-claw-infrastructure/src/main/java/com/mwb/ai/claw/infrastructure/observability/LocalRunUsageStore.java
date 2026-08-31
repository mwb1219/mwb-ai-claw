package com.mwb.ai.claw.infrastructure.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.observability.RunUsage;
import com.mwb.ai.claw.domain.observability.RunUsageStore;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.domain.util.JsonUtils;

/**
 * 本地文件版运行用量存储（默认）：每天一个 JSONL 文件逐行追加运行摘要。
 * <p>
 * 目录默认 {memory-dir}/runs（memory-dir 未配置时为 {user.dir}/.agent/runs），
 * 可按 {@code agent.observability.run-usage-dir} 覆盖。
 * <b>注意</b>：文件落本地盘，多实例各自一份，生产多实例部署建议切到 {code run-usage-store=db}。
 */
public class LocalRunUsageStore implements RunUsageStore {

    private static final Logger log = LoggerFactory.getLogger(LocalRunUsageStore.class);

    private final Path usageDir;

    public LocalRunUsageStore(AgentProperties properties) {
        String dir = properties.getObservability().getRunUsageDir();
        if (dir == null || dir.trim().isEmpty()) {
            String memoryDir = properties.getMemoryDir();
            dir = (memoryDir == null || memoryDir.trim().isEmpty())
                    ? Paths.get(System.getProperty("user.dir"), ".agent", "runs").toString()
                    : Paths.get(memoryDir, "runs").toString();
        }
        this.usageDir = Paths.get(dir);
    }

    @Override
    public void save(RunUsage usage) {
        if (usage == null) {
            return;
        }
        try {
            Files.createDirectories(usageDir);
            Path file = usageDir.resolve(LocalDate.now() + ".jsonl");
            Files.write(file, (JsonUtils.toJson(buildEntry(usage)) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("记录运行用量失败: {}", e.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> findByDate(AgentScope scope, String date) {
        AgentScope s = scope != null ? scope : AgentScope.defaultScope();
        String tenant = nullToEmpty(s.getTenantId());
        String user = nullToEmpty(s.getUserId());
        String day = (date == null || date.trim().isEmpty()) ? LocalDate.now().toString() : date.trim();
        Path file = usageDir.resolve(day + ".jsonl");
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, Object>> runs = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = JsonUtils.fromJson(line, Map.class);
                    if (entry == null) {
                        continue;
                    }
                    // 强制租户/用户隔离：与 scope 不匹配直接跳过
                    String t = nullToEmpty((String) entry.get("tenantId"));
                    String u = nullToEmpty((String) entry.get("userId"));
                    if (!tenant.equals(t) || !user.equals(u)) {
                        continue;
                    }
                    runs.add(entry);
                } catch (Exception ignore) {
                    // 单行解析失败跳过，不中断整体读取
                }
            }
            return runs;
        } catch (IOException e) {
            log.warn("读取运行用量失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<String, Object> buildEntry(RunUsage u) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", LocalDateTime.ofInstant(
                Instant.ofEpochMilli(u.getCreateTime()), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        // 身份维度：写入 JSONL 便于 findByDate 按 tenant/user 过滤
        entry.put("tenantId", nullToEmpty(u.getTenantId()));
        entry.put("userId", nullToEmpty(u.getUserId()));
        entry.put("traceId", u.getTraceId());
        entry.put("sessionId", u.getSessionId());
        entry.put("agentId", u.getAgentId());
        entry.put("orchestration", u.getOrchestration());
        entry.put("model", u.getModel());
        entry.put("durationMs", u.getDurationMs());
        entry.put("success", u.isSuccess());
        entry.put("steps", u.getSteps());
        entry.put("errorCode", u.getErrorCode());
        return entry;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}