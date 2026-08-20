package com.mwb.ai.claw.infrastructure.storage.redis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.mwb.ai.claw.domain.memory.MemoryPage;
import com.mwb.ai.claw.domain.memory.MemoryPageStore;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * Redis 版记忆页存储（agent.storage.type=redis）。
 * <p>
 * key 设计（tenant/user 为空时 ns 退化为 default）：
 * <pre>
 * claw:{ns}:facts                        → Hash（field=事实 key，value=MemoryPage JSON）
 * claw:{ns}:pages:{sessionId}:summary    → Hash（field=pageId，value=MemoryPage JSON）
 * claw:{ns}:pages:{sessionId}:archive    → Hash（field=pageId，value=MemoryPage JSON）
 * claw:{ns}:page-sessions:{type}         → Set（会话索引，供跨会话 listAll* 遍历）
 * </pre>
 */
public class RedisMemoryPageStore implements MemoryPageStore {

    private final StringRedisTemplate redis;

    public RedisMemoryPageStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ==================== 摘要页 ====================

    @Override
    public void saveSummary(AgentScope scope, MemoryPage page) {
        page.setType(MemoryPage.PageType.SUMMARY);
        redis.opsForHash().put(pageHash(scope, page.getSessionId(), "summary"), page.getPageId(), JsonUtils.toJson(page));
        redis.opsForSet().add(pageSessionsKey(scope, "summary"), page.getSessionId());
    }

    @Override
    public List<MemoryPage> loadSummaries(AgentScope scope, String sessionId) {
        return sortedByBlockStart(readHash(redis.opsForHash().entries(pageHash(scope, sessionId, "summary"))));
    }

    @Override
    public List<MemoryPage> listAllSummaries(AgentScope scope) {
        return allPages(scope, "summary");
    }

    // ==================== 事实 ====================

    @Override
    public void appendFact(AgentScope scope, MemoryPage fact) {
        String json = (String) redis.opsForHash().get(factsKey(scope), fact.getKey());
        if (json != null) {
            // 同 key 合并：版本自增，时间戳保留最新（与 JDBC 语义一致）
            MemoryPage old = safeParse(json);
            if (old != null) {
                fact.setVersion(old.getVersion() + 1);
                fact.setCreateTime(Math.max(old.getCreateTime(), fact.getCreateTime()));
            }
        }
        fact.setType(MemoryPage.PageType.FACT);
        redis.opsForHash().put(factsKey(scope), fact.getKey(), JsonUtils.toJson(fact));
    }

    @Override
    public List<MemoryPage> loadFacts(AgentScope scope) {
        List<MemoryPage> facts = readHash(redis.opsForHash().entries(factsKey(scope)));
        facts.sort(Comparator.comparingDouble(MemoryPage::getImportance).reversed());
        return facts;
    }

    @Override
    public void deleteFact(AgentScope scope, String key) {
        redis.opsForHash().delete(factsKey(scope), key);
    }

    @Override
    public void deleteSessionPages(AgentScope scope, String sessionId) {
        redis.delete(pageHash(scope, sessionId, "summary"));
        redis.opsForSet().remove(pageSessionsKey(scope, "summary"), sessionId);
    }

    // ==================== 归档（跨会话 RAG） ====================

    @Override
    public void saveArchive(AgentScope scope, MemoryPage page) {
        page.setType(MemoryPage.PageType.ARCHIVE);
        redis.opsForHash().put(pageHash(scope, page.getSessionId(), "archive"), page.getPageId(), JsonUtils.toJson(page));
        redis.opsForSet().add(pageSessionsKey(scope, "archive"), page.getSessionId());
    }

    @Override
    public List<MemoryPage> loadArchive(AgentScope scope, String sessionId) {
        return sortedByBlockStart(readHash(redis.opsForHash().entries(pageHash(scope, sessionId, "archive"))));
    }

    @Override
    public List<MemoryPage> listAllArchive(AgentScope scope) {
        return allPages(scope, "archive");
    }

    @Override
    public void deleteSessionArchive(AgentScope scope, String sessionId) {
        redis.delete(pageHash(scope, sessionId, "archive"));
        redis.opsForSet().remove(pageSessionsKey(scope, "archive"), sessionId);
    }

    // ==================== 工具方法 ====================

    private List<MemoryPage> allPages(AgentScope scope, String type) {
        Set<String> sessionIds = redis.opsForSet().members(pageSessionsKey(scope, type));
        List<MemoryPage> pages = new ArrayList<>();
        if (sessionIds == null) {
            return pages;
        }
        for (String sessionId : sessionIds) {
            pages.addAll(readHash(redis.opsForHash().entries(pageHash(scope, sessionId, type))));
        }
        pages.sort(Comparator.comparingInt(MemoryPage::getBlockStart));
        return pages;
    }

    private List<MemoryPage> readHash(Map<Object, Object> entries) {
        List<MemoryPage> pages = new ArrayList<>();
        if (entries == null) {
            return pages;
        }
        for (Object value : entries.values()) {
            MemoryPage page = safeParse(String.valueOf(value));
            if (page != null) {
                pages.add(page);
            }
        }
        return pages;
    }

    private List<MemoryPage> sortedByBlockStart(List<MemoryPage> pages) {
        pages.sort(Comparator.comparingInt(MemoryPage::getBlockStart));
        return pages;
    }

    private MemoryPage safeParse(String json) {
        try {
            return JsonUtils.fromJson(json, MemoryPage.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String ns(AgentScope scope) {
        return scope != null ? scope.keyPrefix() : "default";
    }

    private String factsKey(AgentScope scope) {
        return "claw:" + ns(scope) + ":facts";
    }

    private String pageHash(AgentScope scope, String sessionId, String type) {
        return "claw:" + ns(scope) + ":pages:" + sessionId + ":" + type;
    }

    private String pageSessionsKey(AgentScope scope, String type) {
        return "claw:" + ns(scope) + ":page-sessions:" + type;
    }
}
