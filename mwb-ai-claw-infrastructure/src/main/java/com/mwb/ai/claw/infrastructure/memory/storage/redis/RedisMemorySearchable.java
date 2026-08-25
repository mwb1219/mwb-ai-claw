package com.mwb.ai.claw.infrastructure.memory.storage.redis;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.memory.model.MemoryPage;
import com.mwb.ai.claw.domain.memory.store.MemorySearchable;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.redis.RedisSearchTemplate;

/**
 * Memory 检索下推 SPI 的 Redis 实现（db 形态）：关键词全文走 FT.SEARCH（TEXT 倒排），
 * 向量走 FT.SEARCH KNN（FLOAT32 + COSINE）。
 * <p>
 * 由 db 装配的 {@code JdbcMemoryPageStore} 委托调用（pageStore 实现 {@link MemorySearchable}，
 * 召回策略层 {@code instanceof} 分支原样复用）；file 模式不装配，行为零变化。
 * <ul>
 *   <li>检索结果与 file 内存打分「集合接近、排序近似」（方案 8.4 明示差异）；</li>
 *   <li>KNN 的 COSINE 距离 score = 1 - 余弦相似度，返回前转回相似度（1 - score）；</li>
 *   <li>scope 过滤：非空 tenant/user 以 TAG 过滤下推；默认空间（空串）不过滤（条目 key 已隔离）。</li>
 * </ul>
 */
public class RedisMemorySearchable implements MemorySearchable {

    private static final Logger log = LoggerFactory.getLogger(RedisMemorySearchable.class);

    private final RedisSearchTemplate template;

    public RedisMemorySearchable(RedisSearchTemplate template) {
        this.template = template;
    }

    @Override
    public List<MemoryPage> searchFacts(AgentScope scope, List<String> terms, int topK) {
        if (terms == null || terms.isEmpty() || topK <= 0) {
            return new ArrayList<>();
        }
        String query = scopeFilter(scope) + " @page_type:{FACT} (" + orWildcards(terms) + ")";
        List<RedisSearchTemplate.Hit> hits = template.search(template.index("memory"), query.trim(), topK);
        List<MemoryPage> result = new ArrayList<>();
        for (RedisSearchTemplate.Hit hit : hits) {
            result.add(toFactPage(hit));
        }
        log.debug("记忆检索(Redis 下推) 事实命中 {} 条", result.size());
        return result;
    }

    @Override
    public List<MemoryPage> searchPages(AgentScope scope, List<String> terms, int topK) {
        if (terms == null || terms.isEmpty() || topK <= 0) {
            return new ArrayList<>();
        }
        String query = scopeFilter(scope) + " @page_type:{SUMMARY|ARCHIVE} (" + orWildcards(terms) + ")";
        List<RedisSearchTemplate.Hit> hits = template.search(template.index("memory"), query.trim(), topK);
        List<MemoryPage> result = new ArrayList<>();
        for (RedisSearchTemplate.Hit hit : hits) {
            result.add(toPage(hit));
        }
        log.debug("记忆检索(Redis 下推) 记忆页命中 {} 条", result.size());
        return result;
    }

    @Override
    public List<MemoryPage> searchByVector(AgentScope scope, float[] queryVector, int topK) {
        if (queryVector == null || queryVector.length == 0 || topK <= 0) {
            return new ArrayList<>();
        }
        String prefix = scopeFilter(scope) + " @page_type:{SUMMARY|ARCHIVE}";
        List<RedisSearchTemplate.Hit> hits = template.searchKnn(
                template.index("memory"), prefix.trim(), queryVector, topK);
        List<MemoryPage> result = new ArrayList<>();
        for (RedisSearchTemplate.Hit hit : hits) {
            MemoryPage page = toPage(hit);
            if (page != null) {
                result.add(page);
            }
        }
        log.debug("记忆检索(Redis KNN) 命中 {} 条", result.size());
        return result;
    }

    // ==================== 构造与解析 ====================

    /** 词集合转 RediSearch OR 通配子句：%term1%|%term2%。 */
    private String orWildcards(List<String> terms) {
        StringBuilder sb = new StringBuilder();
        for (String term : terms) {
            if (term == null || term.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append('%').append(escapeTerm(term.trim())).append('%');
        }
        return sb.toString();
    }

    /** scope 过滤子句：非空维度才过滤（默认空间不过滤，条目 key 已按 scope 隔离）。 */
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

    private MemoryPage toFactPage(RedisSearchTemplate.Hit hit) {
        MemoryPage page = new MemoryPage();
        page.setPageId(hit.field("page_id"));
        page.setType(MemoryPage.PageType.FACT);
        page.setKey(hit.field("fact_key"));
        page.setContent(hit.field("content"));
        page.setImportance(hit.fieldDouble("importance", 0));
        page.setSessionId(hit.field("session_id"));
        page.setVersion(hit.fieldInt("version", 1));
        page.setTokenCount(hit.fieldInt("token_count", 0));
        page.setCreateTime(hit.fieldLong("create_time", System.currentTimeMillis()));
        return page;
    }

    private MemoryPage toPage(RedisSearchTemplate.Hit hit) {
        String pageId = hit.field("page_id");
        if (pageId == null) {
            return null;
        }
        MemoryPage page = new MemoryPage();
        page.setPageId(pageId);
        page.setType(parseType(hit.field("page_type")));
        page.setContent(hit.field("content"));
        page.setSessionId(hit.field("session_id"));
        page.setBlockStart(hit.fieldInt("block_start", 0));
        page.setBlockEnd(hit.fieldInt("block_end", 0));
        page.setTokenCount(hit.fieldInt("token_count", 0));
        page.setCreateTime(hit.fieldLong("create_time", System.currentTimeMillis()));
        return page;
    }

    private MemoryPage.PageType parseType(String type) {
        if (type == null) {
            return null;
        }
        try {
            return MemoryPage.PageType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** TEXT 通配子句内的特殊字符转义（反斜杠 + RediSearch 语法保留符）。 */
    private static String escapeTerm(String term) {
        StringBuilder sb = new StringBuilder();
        for (char c : term.toCharArray()) {
            if (c == '\\' || c == '|' || c == '(' || c == ')' || c == '[' || c == ']'
                    || c == '{' || c == '}' || c == '"' || c == '@' || c == '%' || c == ' '
                    || c == '\t' || c == '\n') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** TAG 值转义。 */
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
