package com.mwb.ai.claw.infrastructure.memory;

import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.domain.memory.MemoryPageStore;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import com.mwb.ai.claw.infrastructure.util.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
import java.util.stream.Collectors;

/**
 * 文件版记忆页存储：摘要页存 pages/{sessionId}/summary-{blockStart}.json，事实存 facts.jsonl。
 * <p>
 * 目录：{memoryDir}/memory（默认 {user.dir}/.agent/memory）。
 */
@Component
public class FileMemoryPageStore implements MemoryPageStore {

    private static final Logger log = LoggerFactory.getLogger(FileMemoryPageStore.class);

    private final Path memoryDir;
    private final Path pagesDir;
    private final Path factsFile;
    private final Path archiveDir;

    public FileMemoryPageStore(AgentProperties properties) {
        String dir = properties.getMemoryDir();
        if (dir == null || dir.trim().isEmpty()) {
            dir = System.getProperty("user.dir") + "/.agent";
        }
        Path agentDir = Paths.get(dir);
        this.memoryDir = agentDir.resolve("memory");
        this.pagesDir = memoryDir.resolve("pages");
        this.factsFile = memoryDir.resolve("facts.jsonl");
        this.archiveDir = memoryDir.resolve("archive");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(pagesDir);
            Files.createDirectories(archiveDir);
            if (!Files.exists(factsFile)) {
                Files.createFile(factsFile);
            }
            log.info("分层记忆存储目录: {}", memoryDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("初始化分层记忆目录失败", e);
        }
    }

    @Override
    public void saveSummary(MemoryPage page) {
        try {
            Path file = summaryFile(page.getSessionId(), page.getBlockStart());
            Files.createDirectories(file.getParent());
            Files.write(file, JsonUtils.toJson(page).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("保存摘要页失败: {} {}", page.getSessionId(), page.getBlockStart(), e);
        }
    }

    @Override
    public List<MemoryPage> loadSummaries(String sessionId) {
        Path dir = pagesDir.resolve(sessionId);
        if (!Files.exists(dir)) {
            return new ArrayList<>();
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
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
    public List<MemoryPage> listAllSummaries() {
        if (!Files.exists(pagesDir)) {
            return new ArrayList<>();
        }
        List<MemoryPage> all = new ArrayList<>();
        try (java.util.stream.Stream<Path> dirs = Files.list(pagesDir)) {
            for (Path dir : dirs.collect(Collectors.toList())) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                all.addAll(loadSummaries(dir.getFileName().toString()));
            }
        } catch (IOException e) {
            log.warn("列出全部摘要页失败", e);
        }
        return all;
    }

    @Override
    public void appendFact(MemoryPage fact) {
        try {
            Files.createDirectories(memoryDir);
            String line = JsonUtils.toJson(fact);
            Files.write(factsFile, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("追加事实失败: {}", fact.getKey(), e);
        }
    }

    @Override
    public List<MemoryPage> loadFacts() {
        if (!Files.exists(factsFile)) {
            return new ArrayList<>();
        }
        List<MemoryPage> facts = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(factsFile, StandardCharsets.UTF_8)) {
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
    public void deleteFact(String key) {
        List<MemoryPage> facts = loadFacts().stream()
                .filter(f -> !key.equals(f.getKey()))
                .collect(Collectors.toList());
        rewriteFacts(facts);
    }

    @Override
    public void deleteSessionPages(String sessionId) {
        Path dir = pagesDir.resolve(sessionId);
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            for (Path p : files.collect(Collectors.toList())) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("删除会话页失败: {}", sessionId, e);
        }
    }

    @Override
    public void saveArchive(MemoryPage page) {
        try {
            Path dir = archiveDir.resolve(page.getSessionId());
            Files.createDirectories(dir);
            Path file = dir.resolve("archive-" + page.getBlockStart() + ".json");
            Files.write(file, JsonUtils.toJson(page).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("保存归档块失败: {} {}", page.getSessionId(), page.getBlockStart(), e);
        }
    }

    @Override
    public List<MemoryPage> loadArchive(String sessionId) {
        Path dir = archiveDir.resolve(sessionId);
        if (!Files.exists(dir)) {
            return new ArrayList<>();
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
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
    public List<MemoryPage> listAllArchive() {
        if (!Files.exists(archiveDir)) {
            return new ArrayList<>();
        }
        List<MemoryPage> all = new ArrayList<>();
        try (java.util.stream.Stream<Path> dirs = Files.list(archiveDir)) {
            for (Path dir : dirs.collect(Collectors.toList())) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                all.addAll(loadArchive(dir.getFileName().toString()));
            }
        } catch (IOException e) {
            log.warn("列出全部归档块失败", e);
        }
        return all;
    }

    @Override
    public void deleteSessionArchive(String sessionId) {
        Path dir = archiveDir.resolve(sessionId);
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            for (Path p : files.collect(Collectors.toList())) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.warn("删除会话归档失败: {}", sessionId, e);
        }
    }

    private Path summaryFile(String sessionId, int blockStart) {
        return pagesDir.resolve(sessionId).resolve("summary-" + blockStart + ".json");
    }

    private void rewriteFacts(List<MemoryPage> facts) {
        try {
            Files.createDirectories(memoryDir);
            StringBuilder sb = new StringBuilder();
            for (MemoryPage fact : facts) {
                sb.append(JsonUtils.toJson(fact)).append(System.lineSeparator());
            }
            Files.write(factsFile, sb.toString().getBytes(StandardCharsets.UTF_8));
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
