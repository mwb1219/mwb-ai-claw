package com.mwb.ai.claw.infrastructure.observability;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.observability.TraceRun;
import com.mwb.ai.claw.domain.observability.TraceStore;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * 本地文件版步骤级 trace 存储（默认，零依赖）：每个 traceId 一个 JSON 文件。
 * <p>
 * 目录默认 {memory-dir}/traces（memory-dir 未配置时为 {user.dir}/.agent/traces），
 * 可按 {@code agent.observability.trace.dir} 覆盖。
 * <b>注意</b>：文件落本地盘，多实例各自一份，生产多实例部署建议切到 {code store=db}。
 */
public class LocalTraceStore implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(LocalTraceStore.class);

    private final Path traceDir;

    public LocalTraceStore(AgentProperties properties) {
        String dir = properties.getObservability().getTrace().getDir();
        if (dir == null || dir.trim().isEmpty()) {
            String memoryDir = properties.getMemoryDir();
            dir = (memoryDir == null || memoryDir.trim().isEmpty())
                    ? Paths.get(System.getProperty("user.dir"), ".agent", "traces").toString()
                    : Paths.get(memoryDir, "traces").toString();
        }
        this.traceDir = Paths.get(dir);
    }

    @Override
    public void saveTrace(TraceRun trace) {
        if (trace == null || trace.getTraceId() == null || trace.getTraceId().trim().isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(traceDir);
            Path file = traceDir.resolve(sanitize(trace.getTraceId()) + ".json");
            Files.write(file, JsonUtils.toJson(trace).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("保存 trace 失败: {}", e.getMessage());
        }
    }

    @Override
    public TraceRun findTrace(AgentScope scope, String traceId) {
        if (traceId == null || traceId.trim().isEmpty()) {
            return null;
        }
        try {
            Path file = traceDir.resolve(sanitize(traceId) + ".json");
            if (!Files.isRegularFile(file)) {
                return null;
            }
            TraceRun run = JsonUtils.fromJson(
                    new String(Files.readAllBytes(file), StandardCharsets.UTF_8), TraceRun.class);
            return run != null && scopeMatches(scope, run) ? run : null;
        } catch (Exception e) {
            log.warn("读取 trace 失败: {}", e.getMessage());
            return null;
        }
    }

    /** 租户/用户隔离：仅返回当前身份空间内的 trace */
    private boolean scopeMatches(AgentScope scope, TraceRun run) {
        String t = scope == null ? "" : nz(scope.getTenantId());
        String u = scope == null ? "" : nz(scope.getUserId());
        return nz(run.getTenantId()).equals(t) && nz(run.getUserId()).equals(u);
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    /** 清洗 traceId 防止路径穿越 */
    private String sanitize(String id) {
        return id.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}