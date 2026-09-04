package com.mwb.ai.claw.infrastructure.collaboration.delegate.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.util.JsonUtils;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

/**
 * 本地文件版委托编排运行记录存储（{@code agent.collaboration.orchestration-run.store=file}）：
 * 每个 run 一个 JSON 文件落盘于 {@code {dir}}（默认 {@code {user.dir}/.agent/orchestration-runs}）。
 * <p>
 * 单实例持久化（重启不丢），跨请求可续跑；多实例各自一份文件，需共享存储（NFS/对象存储）或将 dir 指到共享目录。
 * 续跑认领用「读 → 单文件 synchronized → 改 phase → 写」CAS，防止同实例并发重复推进。
 */
public class LocalFileOrchestrationRunStore implements OrchestrationRunStore {

    private static final Logger log = LoggerFactory.getLogger(LocalFileOrchestrationRunStore.class);

    private static final String SUFFIX = ".json";

    private final Path runDir;

    public LocalFileOrchestrationRunStore(AgentProperties properties) {
        String dir = properties.getCollaboration().getOrchestrationRun().getDir();
        if (dir == null || dir.trim().isEmpty()) {
            String memoryDir = properties.getMemoryDir();
            dir = (memoryDir == null || memoryDir.trim().isEmpty())
                    ? Paths.get(System.getProperty("user.dir"), ".agent", "orchestration-runs").toString()
                    : Paths.get(memoryDir, "orchestration-runs").toString();
        }
        this.runDir = Paths.get(dir);
    }

    @Override
    public void create(OrchestrationRun run) {
        if (run == null || run.getRunId() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (run.getCreateTime() == 0) {
            run.setCreateTime(now);
        }
        run.setUpdateTime(now);
        write(run);
    }

    @Override
    public void update(OrchestrationRun run) {
        if (run == null || run.getRunId() == null) {
            return;
        }
        run.setVersion(run.getVersion() + 1);
        run.setUpdateTime(System.currentTimeMillis());
        write(run);
    }

    @Override
    public Optional<OrchestrationRun> get(AgentScope scope, String runId) {
        if (runId == null || runId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(read(fileOf(scope, runId)));
    }

    @Override
    public List<OrchestrationRun> listGated(AgentScope scope, String sessionId) {
        String t = nz(scope == null ? "" : scope.getTenantId());
        String u = nz(scope == null ? "" : scope.getUserId());
        List<OrchestrationRun> result = new ArrayList<>();
        for (Path file : listFiles()) {
            OrchestrationRun run = read(file);
            if (run != null && isSameScope(run, t, u)
                    && eq(sessionId, run.getSessionId())
                    && OrchestrationRun.PHASE_GATE.equals(run.getPhase())
                    && run.getGateDecision() == null) {
                result.add(run);
            }
        }
        return result;
    }

    @Override
    public Optional<OrchestrationRun> findGated(AgentScope scope, String sessionId, String layerKey) {
        String t = nz(scope == null ? "" : scope.getTenantId());
        String u = nz(scope == null ? "" : scope.getUserId());
        for (Path file : listFiles()) {
            OrchestrationRun run = read(file);
            if (run != null && isSameScope(run, t, u)
                    && eq(sessionId, run.getSessionId())
                    && eq(layerKey, run.getGateLayer())
                    && OrchestrationRun.PHASE_GATE.equals(run.getPhase())
                    && run.getGateDecision() == null) {
                return Optional.of(run);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean claimForResume(AgentScope scope, String runId) {
        if (runId == null || runId.isEmpty()) {
            return false;
        }
        Path file = fileOf(scope, runId);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        OrchestrationRun run = read(file);
        if (run == null) {
            return false;
        }
        synchronized (run) {
            run = read(file);
            if (run == null) {
                return false;
            }
            if (!(OrchestrationRun.PHASE_GATE.equals(run.getPhase())
                    || OrchestrationRun.PHASE_SUSPENDED.equals(run.getPhase())
                    || OrchestrationRun.PHASE_RUNNING.equals(run.getPhase()))) {
                return false;
            }
            run.setPhase(OrchestrationRun.PHASE_RUNNING);
            run.setVersion(run.getVersion() + 1);
            run.setUpdateTime(System.currentTimeMillis());
            write(run);
            return true;
        }
    }

    @Override
    public void delete(AgentScope scope, String runId) {
        if (runId == null || runId.isEmpty()) {
            return;
        }
        try {
            Files.deleteIfExists(fileOf(scope, runId));
        } catch (IOException e) {
            log.warn("删除编排运行记录文件失败: runId={}, err={}", runId, e.getMessage());
        }
    }

    @Override
    public int deleteStale(long cutoff, int max) {
        int removed = 0;
        for (Path file : listFiles()) {
            if (removed >= max) {
                break;
            }
            OrchestrationRun run = read(file);
            if (run != null && isPendingPhase(run.getPhase()) && run.getUpdateTime() < cutoff) {
                try {
                    Files.deleteIfExists(file);
                    removed++;
                } catch (IOException e) {
                    log.warn("清理悬挂编排运行记录文件失败: {}, err={}", file, e.getMessage());
                }
            }
        }
        return removed;
    }

    // ---------------- 文件读写 ----------------

    private void write(OrchestrationRun run) {
        try {
            Files.createDirectories(runDir);
            Path file = runDir.resolve(fileName(run.getTenantId(), run.getUserId(), run.getRunId()));
            Files.write(file, JsonUtils.toJson(run).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("保存编排运行记录文件失败: runId={}, err={}", run.getRunId(), e.getMessage());
        }
    }

    private OrchestrationRun read(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return JsonUtils.fromJson(json, OrchestrationRun.class);
        } catch (Exception e) {
            log.warn("读取编排运行记录文件失败: {}, err={}", file, e.getMessage());
            return null;
        }
    }

    private Path fileOf(AgentScope scope, String runId) {
        String t = nz(scope == null ? "" : scope.getTenantId());
        String u = nz(scope == null ? "" : scope.getUserId());
        return runDir.resolve(fileName(t, u, runId));
    }

    private List<Path> listFiles() {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(runDir)) {
            return files;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(runDir, "*" + SUFFIX)) {
            for (Path p : stream) {
                files.add(p);
            }
        } catch (IOException e) {
            log.warn("列出编排运行记录目录失败: {}", e.getMessage());
        }
        return files;
    }

    private String fileName(String tenant, String user, String runId) {
        return sanitize(nz(tenant) + "__" + nz(user) + "__" + runId) + SUFFIX;
    }

    /** 清洗文件名字段防止路径穿越 */
    private String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static boolean isSameScope(OrchestrationRun run, String tenant, String user) {
        return nz(run.getTenantId()).equals(tenant) && nz(run.getUserId()).equals(user);
    }

    private static boolean isPendingPhase(String phase) {
        return OrchestrationRun.PHASE_GATE.equals(phase)
                || OrchestrationRun.PHASE_SUSPENDED.equals(phase)
                || OrchestrationRun.PHASE_RUNNING.equals(phase);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}