package com.mwb.ai.claw.infrastructure.memory.strategy;

import com.mwb.ai.claw.domain.llm.EmbeddingGateway;
import com.mwb.ai.claw.domain.memory.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.domain.memory.MemoryPageStore;
import com.mwb.ai.claw.domain.memory.MemoryRetriever;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量记忆检索器（Phase 3）：对事实 + 摘要 + 档案按向量余弦相似度召回。
 * <p>
 * 候选页向量惰性生成并缓存到磁盘（.agent/memory/vectors/[namespace/]&lt;pageId&gt;.json），
 * 避免每次检索重复调用 embedding 服务；embedding 失败（空向量）时返回空结果，
 * 由 {@link HybridMemoryRetriever} 融合层回退到关键词检索。
 * 多租户模式下向量目录与内存缓存 key 均按 scope 隔离。
 */
@Component
public class VectorMemoryRetriever implements MemoryRetriever {

    private static final Logger log = LoggerFactory.getLogger(VectorMemoryRetriever.class);

    private final MemoryPageStore pageStore;
    private final EmbeddingGateway embeddingGateway;
    private final LayeredMemoryConfig config;
    private final Path vectorsDir;

    /** 内存向量缓存：scopePrefix:pageId → vector（避免重复调用 embedding） */
    private final Map<String, float[]> cache = new HashMap<>();

    public VectorMemoryRetriever(MemoryPageStore pageStore,
                                 EmbeddingGateway embeddingGateway,
                                 AgentProperties properties) {
        this.pageStore = pageStore;
        this.embeddingGateway = embeddingGateway;
        this.config = properties.getMemory();
        String dir = properties.getMemoryDir();
        if (dir == null || dir.trim().isEmpty()) {
            dir = System.getProperty("user.dir") + "/.agent";
        }
        this.vectorsDir = Paths.get(dir).resolve("memory").resolve("vectors");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(vectorsDir);
        } catch (IOException e) {
            log.warn("初始化向量目录失败: {}", vectorsDir, e);
        }
    }

    @Override
    public List<MemoryPage> search(AgentScope scope, String query, int topK) {
        if (!config.isVectorEnabled()) {
            return new ArrayList<>();
        }
        if (query == null || query.trim().isEmpty() || topK <= 0) {
            return new ArrayList<>();
        }
        float[] queryVec = embeddingGateway.embed(query);
        if (queryVec == null || queryVec.length == 0) {
            return new ArrayList<>();
        }

        // 候选 = 事实 + 摘要 + 档案（跨会话，多 Agent 共享，scope 隔离）
        List<MemoryPage> candidates = new ArrayList<>();
        candidates.addAll(pageStore.loadFacts(scope));
        candidates.addAll(pageStore.listAllSummaries(scope));
        candidates.addAll(pageStore.listAllArchive(scope));

        List<ScoredPage> scored = new ArrayList<>();
        for (MemoryPage page : candidates) {
            float[] vec = vectorOf(scope, page);
            if (vec == null || vec.length == 0) {
                continue;
            }
            float score = cosine(queryVec, vec);
            if (score > 0) {
                scored.add(new ScoredPage(page, score));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredPage::getScore).reversed());

        List<MemoryPage> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            result.add(scored.get(i).page);
        }
        log.debug("向量记忆检索 '{}' 命中 {} 条", query, result.size());
        return result;
    }

    // ==================== 私有方法 ====================

    /** 取候选页向量：内存缓存 → 磁盘缓存 → 计算并写盘（缓存 key 带 scope 前缀防串户） */
    private float[] vectorOf(AgentScope scope, MemoryPage page) {
        String pageId = page.getPageId();
        if (pageId == null || pageId.isEmpty()) {
            return null;
        }
        String cacheKey = (scope != null ? scope.keyPrefix() : "default") + ":" + pageId;
        float[] cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        float[] loaded = loadFromDisk(scope, pageId);
        if (loaded == null) {
            loaded = embeddingGateway.embed(page.getContent());
            if (loaded == null || loaded.length == 0) {
                return null;
            }
            saveToDisk(scope, pageId, loaded);
        }
        cache.put(cacheKey, loaded);
        return loaded;
    }

    private Path vectorFile(AgentScope scope, String pageId) {
        String name = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(pageId.getBytes(StandardCharsets.UTF_8)) + ".json";
        Path base = vectorsDir;
        String ns = scope != null ? scope.namespace() : null;
        if (ns != null) {
            base = base.resolve(ns);
        }
        return base.resolve(name);
    }

    private float[] loadFromDisk(AgentScope scope, String pageId) {
        try {
            Path file = vectorFile(scope, pageId);
            if (!Files.exists(file)) {
                return null;
            }
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            double[] arr = JsonUtils.fromJson(json, double[].class);
            float[] vec = new float[arr.length];
            for (int i = 0; i < arr.length; i++) {
                vec[i] = (float) arr[i];
            }
            return vec;
        } catch (Exception e) {
            log.warn("加载向量缓存失败: {}", pageId, e);
            return null;
        }
    }

    private void saveToDisk(AgentScope scope, String pageId, float[] vec) {
        try {
            double[] arr = new double[vec.length];
            for (int i = 0; i < vec.length; i++) {
                arr[i] = vec[i];
            }
            Path file = vectorFile(scope, pageId);
            Files.createDirectories(file.getParent());
            Files.write(file, JsonUtils.toJson(arr).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("保存向量缓存失败: {}", pageId, e);
        }
    }

    private float cosine(float[] a, float[] b) {
        if (a.length == 0 || b.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    private static class ScoredPage {
        final MemoryPage page;
        final float score;

        ScoredPage(MemoryPage page, float score) {
            this.page = page;
            this.score = score;
        }

        float getScore() {
            return score;
        }
    }
}
