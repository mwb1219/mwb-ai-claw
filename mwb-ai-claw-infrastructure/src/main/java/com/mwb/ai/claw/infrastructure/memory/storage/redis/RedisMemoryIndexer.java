package com.mwb.ai.claw.infrastructure.memory.storage.redis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.llm.EmbeddingGateway;
import com.mwb.ai.claw.domain.memory.layered.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.layered.model.MemoryPage;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.redis.RedisSearchTemplate;

/**
 * Memory 索引的 Redis 双写器（db 形态，MySQL 权威存储 + Redis 派生检索索引）。
 * <p>
 * 由 db 装配的 {@code JdbcMemoryPageStore} 在写 MySQL 成功后同步调用：
 * <ul>
 *   <li>写入：事实 / 摘要 / 归档 upsert 到 {@code {prefix}:memory:entry:*} Hash（含向量 float4）；</li>
 *   <li>删除：按条目 key 或按 (sessionId, pageType) 反查后 DEL；</li>
 *   <li>索引懒创建：首次带向量写入时以维度确定 {@code embedding VECTOR DIM}；</li>
 *   <li>Redis 失败不阻断主事务（异常由 RedisSearchTemplate 吞掉并记日志，靠重建自愈）。</li>
 * </ul>
 */
public class RedisMemoryIndexer {

    private static final Logger log = LoggerFactory.getLogger(RedisMemoryIndexer.class);

    /** 索引存在性确认的 TTL（毫秒）：避免每次写入都发 FT.INFO。 */
    private static final long INDEX_CONFIRM_TTL = 30_000L;

    private final RedisSearchTemplate template;
    private final EmbeddingGateway embeddingGateway;
    private final LayeredMemoryConfig config;

    /** 已建索引维度（0 = 未记录）。 */
    private volatile int indexDimensions;
    /** 索引是否已初始化（维度记录与否都阻止重复建索引）。 */
    private volatile boolean indexInitialized;
    /** 最近一次确认索引存在的时刻（0 = 未确认），用于自愈检测外部删索引/清空 Redis。 */
    private volatile long lastIndexConfirmedAt;

    public RedisMemoryIndexer(RedisSearchTemplate template,
                              EmbeddingGateway embeddingGateway,
                              LayeredMemoryConfig config) {
        this.template = template;
        this.embeddingGateway = embeddingGateway;
        this.config = config;
    }

    // ==================== 写入 ====================

    /** 事实页 upsert（MySQL appendFact 成功后调用）。 */
    public void upsertFact(AgentScope scope, MemoryPage fact) {
        if (fact == null) {
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("page_id", "fact-" + fact.getKey());
        fields.put("page_type", "FACT");
        fields.put("session_id", fact.getSessionId());
        fields.put("fact_key", fact.getKey());
        fields.put("content", fact.getContent());
        fields.put("importance", fact.getImportance());
        fields.put("version", fact.getVersion());
        fields.put("token_count", fact.getTokenCount());
        fields.put("block_start", 0);
        fields.put("block_end", 0);
        fields.put("create_time", fact.getCreateTime());
        putWithVector(scope, "fact-" + fact.getKey(), fact.getContent(), fields);
    }

    /** 记忆页 upsert（saveSummary / saveArchive 成功后调用）。 */
    public void upsertPage(AgentScope scope, MemoryPage page) {
        if (page == null || page.getPageId() == null) {
            return;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("page_id", page.getPageId());
        fields.put("page_type", page.getType() == null ? "" : page.getType().name());
        fields.put("session_id", page.getSessionId());
        fields.put("fact_key", "");
        fields.put("content", page.getContent());
        fields.put("importance", page.getImportance());
        fields.put("version", page.getVersion());
        fields.put("token_count", page.getTokenCount());
        fields.put("block_start", page.getBlockStart());
        fields.put("block_end", page.getBlockEnd());
        fields.put("create_time", page.getCreateTime());
        putWithVector(scope, page.getPageId(), page.getContent(), fields);
    }

    private void putWithVector(AgentScope scope, String pageId, String content, Map<String, Object> fields) {
        fields.put("tenant_id", tid(scope));
        fields.put("user_id", uid(scope));
        // 向量只存在于 Redis 派生索引；配置未开启向量或 embedding 失败时跳过向量字段（可被全文命中）
        if (config.isVectorEnabled()) {
            float[] vector = embeddingGateway.embed(content);
            if (vector != null && vector.length > 0) {
                ensureIndex(vector.length);
                if (indexDimensions == vector.length) {
                    fields.put("embedding", RedisSearchTemplate.toFloatBytes(vector));
                }
            }
        }
        template.hset(entryKey(scope, pageId), fields);
    }

    // ==================== 删除 ====================

    /** 删除事实条目（MySQL deleteFact 成功后调用）。 */
    public void deleteFact(AgentScope scope, String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        template.delete(entryKey(scope, "fact-" + key));
    }

    /** 删除会话下全部摘要页条目（MySQL deleteSessionPages 成功后调用）。 */
    public void deleteSessionPages(AgentScope scope, String sessionId) {
        deleteBySessionType(scope, sessionId, "SUMMARY");
    }

    /** 删除会话下全部归档页条目（MySQL deleteSessionArchive 成功后调用）。 */
    public void deleteSessionArchive(AgentScope scope, String sessionId) {
        deleteBySessionType(scope, sessionId, "ARCHIVE");
    }

    private void deleteBySessionType(AgentScope scope, String sessionId, String pageType) {
        StringBuilder query = new StringBuilder("@page_type:{").append(pageType).append("}");
        if (sessionId != null && !sessionId.isEmpty()) {
            query.append(" @session_id:{").append(escapeTag(sessionId)).append("}");
        }
        String scopeFilter = scopeFilter(scope);
        if (!scopeFilter.isEmpty()) {
            query.append(" ").append(scopeFilter);
        }
        List<String> keys = template.keysByQuery(template.index("memory"), query.toString(), 1000);
        if (keys != null && !keys.isEmpty()) {
            template.delete(keys.toArray(new String[0]));
        }
    }

    // ==================== T6：过期清理（DB 清理时同步失效派生索引） ====================

    /** 清理 create_time 早于 cutoff 的页面/FACT 派生索引条目（跨所有 scope 全局执行，供定时任务在 DB 清理后调用）。 */
    public void cleanExpired(long cutoff) {
        try {
            // 同时覆盖 FACT / SUMMARY / ARCHIVE：create_time 数值范围查询
            List<String> keys = template.keysByQuery(
                    template.index("memory"), "@create_time:[-inf (" + cutoff + "]", 10000);
            if (keys != null && !keys.isEmpty()) {
                template.delete(keys.toArray(new String[0]));
                log.info("Memory Redis 索引过期清理: 删除 {} 条 (cutoff={})", keys.size(), cutoff);
            }
        } catch (Exception e) {
            log.warn("Memory Redis 索引过期清理失败: {}", e.getMessage());
        }
    }

    // ==================== 索引管理 ====================

    /** 懒建 Memory 索引：维度以首次向量写入确定，后续维度变化跳过向量字段；索引被外部
     *  删除/清空 Redis 时自愈重建（FT.CREATE 接管 PREFIX 下已写入的 entry）。 */
    private void ensureIndex(int dimensions) {
        long now = System.currentTimeMillis();
        if (indexDimensions == dimensions && now - lastIndexConfirmedAt < INDEX_CONFIRM_TTL) {
            return;
        }
        synchronized (this) {
            if (indexDimensions == dimensions
                    && System.currentTimeMillis() - lastIndexConfirmedAt < INDEX_CONFIRM_TTL) {
                return;
            }
            String index = template.index("memory");
            if (template.indexExists(index)) {
                // 索引已存在（含重启后 Redis 保留）：读取实际维度并对齐，否则后续
                // 写入会因维度不匹配而跳过 embedding，导致新记忆无法被向量 KNN 检索到。
                int existing = template.indexDimensions(index);
                indexDimensions = existing > 0 ? existing : dimensions;
                indexInitialized = true;
                lastIndexConfirmedAt = System.currentTimeMillis();
                if (existing > 0 && existing != dimensions) {
                    log.warn("Memory Redis 索引已存在（维度 {}），与当前向量维度 {} 不一致，跳过向量写入",
                            existing, dimensions);
                }
                return;
            }
            // 索引不存在（首次创建 / 外部删除或清空 Redis）：重建
            indexInitialized = false;
            indexDimensions = 0;
            template.createIndex(index, template.entryPrefix("memory"),
                    "tenant_id TAG user_id TAG page_id TAG page_type TAG session_id TAG"
                            + " fact_key TEXT content TEXT importance NUMERIC block_start NUMERIC"
                            + " create_time NUMERIC"
                            + " embedding VECTOR FLAT 6 TYPE FLOAT32 DIM " + dimensions
                            + " DISTANCE_METRIC COSINE");
            indexDimensions = dimensions;
            indexInitialized = true;
            lastIndexConfirmedAt = System.currentTimeMillis();
            log.info("创建 Memory Redis 索引: {}（维度 {}）", index, dimensions);
        }
    }

    // ==================== 工具 ====================

    /** 条目 key：{prefix}:memory:entry:[scope/]pageId（scope 隔离防串户）。 */
    private String entryKey(AgentScope scope, String pageId) {
        String scopeKey = scopeKey(scope);
        return template.entryPrefix("memory") + (scopeKey.isEmpty() ? "" : scopeKey + ":") + pageId;
    }

    private String scopeKey(AgentScope scope) {
        if (scope == null) {
            return "";
        }
        return (scope.getTenantId() == null ? "" : scope.getTenantId())
                + "/" + (scope.getUserId() == null ? "" : scope.getUserId());
    }

    /** scope 过滤子句：非空维度才过滤（默认空间不携带，条目天然隔离在各自 key 下）。 */
    private String scopeFilter(AgentScope scope) {
        if (scope == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (scope.getTenantId() != null && !scope.getTenantId().isEmpty()) {
            sb.append("@tenant_id:{").append(escapeTag(scope.getTenantId())).append("}");
        }
        if (scope.getUserId() != null && !scope.getUserId().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("@user_id:{").append(escapeTag(scope.getUserId())).append("}");
        }
        return sb.toString();
    }

    private String tid(AgentScope scope) {
        return scope == null || scope.getTenantId() == null ? "" : scope.getTenantId();
    }

    private String uid(AgentScope scope) {
        return scope == null || scope.getUserId() == null ? "" : scope.getUserId();
    }

    /** TAG 值转义：RediSearch TAG 查询需转义分隔符与括号。 */
    private static String escapeTag(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == ',' || c == '.' || c == '_' || c == '-' || c == '|' || c == '(' || c == ')'
                    || c == '{' || c == '}' || c == '\\' || c == '"') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
