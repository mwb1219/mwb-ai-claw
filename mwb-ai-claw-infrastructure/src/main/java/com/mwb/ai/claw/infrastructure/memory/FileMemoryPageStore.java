package com.mwb.ai.claw.infrastructure.memory;

import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.domain.memory.MemoryPageStore;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import com.mwb.ai.claw.infrastructure.util.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件版记忆页存储：摘要页存 pages/[namespace/]{sessionId}/summary-{blockStart}.json，事实存 facts.jsonl。
 * <p>
 * 目录：{memoryDir}/memory（默认 {user.dir}/.agent/memory）。
 * 多租户模式下按 namespace（tenant/user）分目录隔离；legacy 模式保持原扁平布局。
 */
public class FileMemoryPageStore implements MemoryPageStore {

    private static final Logger log = LoggerFactory.getLogger(FileMemoryPageStore.class);

    private final Path memoryDir;
    private final Path pagesDir;
    private final Path archiveDir;
    private final ConcurrentMap<String, Object> factsLocks = new ConcurrentHashMap<>();

    public FileMemoryPageStore(AgentProperties properties) {
        String dir = properties.getMemoryDir();
        if (dir == null || dir.trim().isEmpty()) {
            dir = System.getProperty("user.dir") + "/.agent";
        }
        Path agentDir = Paths.get(dir);
        this.memoryDir = agentDir.resolve("memory");
        this.pagesDir = memoryDir.resolve("pages");
        this.archiveDir = memoryDir.resolve("archive");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(pagesDir);
            Files.createDirectories(archiveDir);
            log.warn("分层记忆存储目录: {}", memoryDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("初始化分层记忆目录失败", e);
        }
    }

    // ==================== scope 目录解析 ====================

    /** namespace 对应的基础目录；legacy（无 namespace）时为根目录 */
    private Path scopeDir(Path base, AgentScope scope) {
        String ns = scope != null ? scope.namespace() : null;
        return ns != null ? base.resolve(ns) : base;
    }

    private Object factsLock(AgentScope scope) {
        String key = scope != null ? scope.keyPrefix() : "default";
        return factsLocks.computeIfAbsent(key, k -> new Object());
    }

    // ==================== MemoryPageStore 实现 ====================

    @Override
    public void saveSummary(AgentScope scope, MemoryPage page) {
        try {
            Path file = summaryFile(scope, page.getSessionId(), page.getBlockStart());
            Files.createDirectories(file.getParent());
            Files.write(file, JsonUtils.toJson(page).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("保存摘要页失败: {} {}", page.getSessionId(), page.getBlockStart(), e);
        }
    }

    @Override
    public List<MemoryPage> loadSummaries(AgentScope scope, String sessionId) {
        Path dir = scopeDir(pagesDir, scope).resolve(sessionId);
        if (!Files.exists(dir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("summary-"))
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> {
                        try {
                            String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                            return JsonUtils.fromJson(json, MemoryPage.class);
                        } catch (Exception e) {
                            log.warn("加载摘要页失败: {}", p.getFileName(), e);
                            return null;
                        }
                    })
                    .filter(page -> page != null)
                    .sorted(Comparator.comparingInt(MemoryPage::getBlockStart))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("列出摘要页失败: {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<MemoryPage> listAllSummaries(AgentScope scope) {
        Path base = scopeDir(pagesDir, scope);
        if (!Files.exists(base)) {
            return new ArrayList<>();
        }
        List<MemoryPage> all = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(base)) {
            for (Path dir : dirs.collect(Collectors.toList())) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                all.addAll(loadSummaries(scope, dir.getFileName().toString()));
            }
        } catch (IOException e) {
            log.warn("列出全部摘要页失败", e);
        }
        return all;
    }

    @Override
    public void appendFact(AgentScope scope, MemoryPage fact) {
        synchronized (factsLock(scope)) {
            try {
                Path file = factsFile(scope);
                Files.createDirectories(file.getParent());
                String line = JsonUtils.toJson(fact);
                Files.write(file, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.error("追加事实失败: {}", fact.getKey(), e);
            }
        }
    }

    @Override
    public List<MemoryPage> loadFacts(AgentScope scope) {
        Path file = factsFile(scope);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        List<MemoryPage> facts = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                try {
                    facts.add(JsonUtils.fromJson(line, MemoryPage.class));
                } catch (Exception e) {
                    log.warn("解析事实行失败，已跳过: {}", truncate(line));
                }
            }
        } catch (IOException e) {
            log.warn("读取事实文件失败", e);
        }
        return facts;
    }

    @Override
    public void deleteFact(AgentScope scope, String key) {
        synchronized (factsLock(scope)) {
            List<MemoryPage> facts = loadFacts(scope).stream()
                    .filter(f -> !key.equals(f.getKey()))
                    .collect(Collectors.toList());
            rewriteFacts(scope, facts);
        }
    }

    @Override
    public void deleteSessionPages(AgentScope scope, String sessionId) {
        Path dir = scopeDir(pagesDir, scope).resolve(sessionId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.collect(Collectors.toList())) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("删除会话页失败: {}", sessionId, e);
        }
    }

    @Override
    public void saveArchive(AgentScope scope, MemoryPage page) {
        try {
            Path dir = scopeDir(archiveDir, scope).resolve(page.getSessionId());
            Files.createDirectories(dir);
            Path file = dir.resolve("archive-" + page.getBlockStart() + ".json");
            Files.write(file, JsonUtils.toJson(page).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("保存归档块失败: {} {}", page.getSessionId(), page.getBlockStart(), e);
        }
    }

    @Override
    public List<MemoryPage> loadArchive(AgentScope scope, String sessionId) {
        Path dir = scopeDir(archiveDir, scope).resolve(sessionId);
        if (!Files.exists(dir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("archive-"))
                    .filter(p -> p.toString().endsWith(".json"))
                    .map(p -> {
                        try {
                            String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                            return JsonUtils.fromJson(json, MemoryPage.class);
                        } catch (Exception e) {
                            log.warn("加载归档块失败: {}", p.getFileName(), e);
                            return null;
                        }
                    })
                    .filter(page -> page != null)
                    .sorted(Comparator.comparingInt(MemoryPage::getBlockStart))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("列出归档块失败: {}", sessionId, e);
            return new ArrayList<>();
        }
    }

    @Override
    public List<MemoryPage> listAllArchive(AgentScope scope) {
        Path base = scopeDir(archiveDir, scope);
        if (!Files.exists(base)) {
            return new ArrayList<>();
        }
        List<MemoryPage> all = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(base)) {
            for (Path dir : dirs.collect(Collectors.toList())) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                all.addAll(loadArchive(scope, dir.getFileName().toString()));
            }
        } catch (IOException e) {
            log.warn("列出全部归档块失败", e);
        }
        return all;
    }

    @Override
    public void deleteSessionArchive(AgentScope scope, String sessionId) {
        Path dir = scopeDir(archiveDir, scope).resolve(sessionId);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.collect(Collectors.toList())) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("删除会话归档失败: {}", sessionId, e);
        }
    }

    // ==================== 工具方法 ====================

    private Path summaryFile(AgentScope scope, String sessionId, int blockStart) {
        return scopeDir(pagesDir, scope).resolve(sessionId).resolve("summary-" + blockStart + ".json");
    }

    /** legacy 模式下保持 memory/facts.jsonl 原路径，多租户下为 memory/facts/{namespace}/facts.jsonl */
    private Path factsFile(AgentScope scope) {
        String ns = scope != null ? scope.namespace() : null;
        return ns != null ? memoryDir.resolve("facts").resolve(ns).resolve("facts.jsonl")
                : memoryDir.resolve("facts.jsonl");
    }

    private void rewriteFacts(AgentScope scope, List<MemoryPage> facts) {
        try {
            Path file = factsFile(scope);
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            for (MemoryPage fact : facts) {
                sb.append(JsonUtils.toJson(fact)).append(System.lineSeparator());
            }
            Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("重写事实文件失败", e);
        }
    }

    /** 事实 token 数在持久化时由内容估算 */
    public static int tokenOf(MemoryPage page) {
        return TokenEstimator.estimate(page);
    }

    private String truncate(String text) {
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }
}
