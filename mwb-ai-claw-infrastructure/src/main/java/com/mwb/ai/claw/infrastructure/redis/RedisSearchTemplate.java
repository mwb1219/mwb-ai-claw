package com.mwb.ai.claw.infrastructure.redis;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;

/**
 * Redis Stack（RediSearch）检索索引模板：封装 FT.* 原生命令。
 * <p>
 * RediSearch 无 Spring 官方 API，这里通过 Lettuce 原生异步连接
 * {@link RedisAsyncCommands} 的 {@code dispatch(ProtocolKeyword, CommandOutput, CommandArgs)}
 * 重载 + {@link NestedMultiOutput} 发送并解析原生命令（FT.CREATE / FT.INFO /
 * FT.DROPINDEX / FT.SEARCH / HSET / DEL），避免默认 ByteArrayOutput 无法解析
 * FT.SEARCH 响应首元素 integer（count）的问题。原生连接经
 * {@link RedisConnection#getNativeConnection()} 获取（接口方法，可穿透 Spring
 * 对连接做的 JDK 动态代理）。
 * <ul>
 *   <li>关键词全文与向量 KNN 共用一个索引（schema 由调用方构造）；</li>
 *   <li>向量以 float4 小端字节序存储（与 RediSearch VECTOR TYPE FLOAT32 编码一致）；</li>
 *   <li>KNN 的 COSINE 距离 score = 1 - cosine_similarity，调用方转相似度需 {@code 1 - score}；</li>
 *   <li>Redis 故障/命令异常不向上抛业务异常，由调用方按「派生索引可重建」策略处理。</li>
 * </ul>
 */
public class RedisSearchTemplate {

    private static final Logger log = LoggerFactory.getLogger(RedisSearchTemplate.class);

    /** 索引统一前缀：{indexPrefix}:{name}:idx。 */
    private static final String INDEX_SUFFIX = ":idx";

    /** 条目统一前缀：{indexPrefix}:{name}:entry:。 */
    private static final String ENTRY_SUFFIX = ":entry:";

    private final StringRedisTemplate redis;
    private final String indexPrefix;

    public RedisSearchTemplate(StringRedisTemplate redis, String indexPrefix) {
        this.redis = redis;
        this.indexPrefix = indexPrefix == null || indexPrefix.trim().isEmpty()
                ? "claw" : indexPrefix.trim();
    }

    /** 索引完整名（如 claw:memory:idx）。 */
    public String index(String name) {
        return indexPrefix + ":" + name + INDEX_SUFFIX;
    }

    /** 条目 key 前缀（如 claw:memory:entry:），配合 FT.CREATE PREFIX 使用。 */
    public String entryPrefix(String name) {
        return indexPrefix + ":" + name + ENTRY_SUFFIX;
    }

    // ==================== 索引管理 ====================

    /** FT.CREATE {index} ON HASH PREFIX 1 {keyPrefix} SCHEMA {schema} */
    public void createIndex(String index, String keyPrefix, String schema) {
        execute(connection -> {
            List<Object> args = new ArrayList<>();
            args.add(index);
            args.add("ON");
            args.add("HASH");
            args.add("PREFIX");
            args.add("1");
            args.add(keyPrefix);
            args.add("SCHEMA");
            for (String field : schema.split("\\s+")) {
                if (!field.isEmpty()) {
                    args.add(field);
                }
            }
            executeCommand(connection, "FT.CREATE", args);
            return null;
        });
    }

    /** FT.INFO {index}：索引存在性探测。 */
    public boolean indexExists(String index) {
        try {
            Object result = execute(connection -> executeCommand(connection, "FT.INFO", Arrays.asList(index)));
            return result != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * FT.INFO {index} 读取 embedding 向量维度（dim），用于索引已存在（如重启后
     * Redis 保留）时对齐维度，避免后续 HSET 因维度不匹配而跳过向量写入。
     * <p>
     * 兼容 RESP2 扁平数组与 RESP3（NestedMultiOutput 压平的键值对）两种格式：
     * 顶层找 {@code attributes}，其内每个 attribute 也是扁平键值对，定位
     * identifier/attribute=embedding 且 type=VECTOR 的项后返回 dim。
     *
     * @return 向量维度；未找到或执行失败返回 -1
     */
    public int indexDimensions(String index) {
        try {
            Object result = execute(connection -> executeCommand(connection, "FT.INFO", Arrays.asList(index)));
            if (!(result instanceof List)) {
                return -1;
            }
            return findVectorDim((List<?>) result);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private int findVectorDim(List<?> root) {
        List<?> attributes = null;
        for (int i = 0; i + 1 < root.size(); i += 2) {
            if ("attributes".equals(asString(root.get(i))) && root.get(i + 1) instanceof List) {
                attributes = (List<?>) root.get(i + 1);
                break;
            }
        }
        if (attributes == null) {
            return -1;
        }
        for (Object attrObj : attributes) {
            if (!(attrObj instanceof List)) {
                continue;
            }
            List<?> attr = (List<?>) attrObj;
            boolean isEmbedding = false;
            boolean isVector = false;
            String dimValue = null;
            for (int i = 0; i + 1 < attr.size(); i += 2) {
                String key = asString(attr.get(i));
                String value = asString(attr.get(i + 1));
                if (("identifier".equals(key) || "attribute".equals(key)) && "embedding".equals(value)) {
                    isEmbedding = true;
                }
                if ("type".equals(key) && "VECTOR".equalsIgnoreCase(value)) {
                    isVector = true;
                }
                if ("dim".equals(key)) {
                    dimValue = value;
                }
            }
            if (isEmbedding && isVector && dimValue != null) {
                try {
                    return Integer.parseInt(dimValue.trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** FT.DROPINDEX {index} DD：删除索引（保留条目数据，可重建）。 */
    public void dropIndex(String index) {
        execute(connection -> {
            executeCommand(connection, "FT.DROPINDEX", Arrays.asList(index, "DD"));
            return null;
        });
    }

    // ==================== 写入 ====================

    /** HSET {key} field1 value1 ...：字段值为 String / byte[] / Number / Boolean。 */
    public void hset(String key, Map<String, Object> fields) {
        if (key == null || fields == null || fields.isEmpty()) {
            return;
        }
        execute(connection -> {
            List<Object> args = new ArrayList<>();
            args.add(key);
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                args.add(entry.getKey());
                args.add(entry.getValue());
            }
            executeCommand(connection, "HSET", args);
            return null;
        });
    }

    /** DEL key...：返回删除条数（Redis 故障时返回 0）。 */
    public long delete(String... keys) {
        if (keys == null || keys.length == 0) {
            return 0;
        }
        Long deleted = execute(connection -> {
            List<Object> args = new ArrayList<>();
            for (String key : keys) {
                args.add(key);
            }
            return (Long) executeCommand(connection, "DEL", args);
        });
        return deleted == null ? 0 : deleted;
    }

    // ==================== 检索 ====================

    /**
     * 全文 / 标签检索：FT.SEARCH {index} {query} LIMIT 0 {limit}。
     *
     * @return 命中列表（全文检索未请求 WITHSCORES，score 默认 0；KNN 命中则取
     *         {@code AS score} 距离，见 {@link #searchKnn}）
     */
    public List<Hit> search(String index, String query, int limit) {
        List<Hit> hits = execute(connection -> {
            List<Object> args = new ArrayList<>();
            args.add(index);
            args.add(query);
            args.add("LIMIT");
            args.add("0");
            args.add(String.valueOf(limit));
            return parseSearch(executeCommand(connection, "FT.SEARCH", args));
        });
        return hits == null ? new ArrayList<>() : hits;
    }

    /**
     * 向量 KNN 检索：FT.SEARCH {index} "{prefix}=>[KNN {limit} @embedding $vec AS score]"
     * PARAMS 2 vec {float4} SORTBY score ASC LIMIT 0 {limit} DIALECT 2。
     * <p>
     * 注意：RediSearch 的 KNN（hybrid）语法强制要求 {@code DIALECT 2}，缺省会按旧方言解析
     * {@code =>[KNN...} 而报语法错误；前缀过滤需整体用括号包裹（{@code (<filter>)=>[KNN ...]}）。
     *
     * @param prefix 查询前缀（如 "@page_type:{SUMMARY} @tenant_id:{t1}"，可为空串表示不过滤）
     * @return 命中列表（score 为 COSINE 距离，越小越相似；相似度 = 1 - score）
     */
    public List<Hit> searchKnn(String index, String prefix, float[] vector, int limit) {
        if (vector == null || vector.length == 0) {
            return new ArrayList<>();
        }
        String base = (prefix == null || prefix.trim().isEmpty()) ? "*" : "(" + prefix.trim() + ")";
        List<Hit> hits = execute(connection -> {
            List<Object> args = new ArrayList<>();
            args.add(index);
            args.add(base + "=>[KNN " + limit + " @embedding $vec AS score]");
            args.add("PARAMS");
            args.add("2");
            args.add("vec");
            args.add(toFloatBytes(vector));
            args.add("SORTBY");
            args.add("score");
            args.add("ASC");
            args.add("LIMIT");
            args.add("0");
            args.add(String.valueOf(limit));
            args.add("DIALECT");
            args.add("2");
            return parseSearch(executeCommand(connection, "FT.SEARCH", args));
        });
        return hits == null ? new ArrayList<>() : hits;
    }

    /**
     * 按查询取文档 key（FT.SEARCH ... RETURN 0），用于删除/重建。
     */
    public List<String> keysByQuery(String index, String query, int limit) {
        List<String> keys = execute(connection -> {
            List<Object> args = new ArrayList<>();
            args.add(index);
            args.add(query);
            args.add("RETURN");
            args.add("0");
            args.add("LIMIT");
            args.add("0");
            args.add(String.valueOf(limit));
            Object result = executeCommand(connection, "FT.SEARCH", args);
            List<String> found = new ArrayList<>();
            if (!(result instanceof List)) {
                return found;
            }
            List<?> root = (List<?>) result;
            if (root.size() < 2) {
                return found;
            }
            if (isJsonFormat(root)) {
                // RESP3 JSON 格式：key 在每条命中的 id 字段
                for (int i = 0; i + 1 < root.size(); i += 2) {
                    if (!"results".equals(asString(root.get(i))) || !(root.get(i + 1) instanceof List)) {
                        continue;
                    }
                    for (Object hitObj : (List<?>) root.get(i + 1)) {
                        if (!(hitObj instanceof List)) {
                            continue;
                        }
                        List<?> hit = (List<?>) hitObj;
                        for (int j = 0; j + 1 < hit.size(); j += 2) {
                            if ("id".equals(asString(hit.get(j)))) {
                                String key = asString(hit.get(j + 1));
                                if (key != null && !key.isEmpty()) {
                                    found.add(key);
                                }
                            }
                        }
                    }
                    break;
                }
                return found;
            }
            // RESP2 RETURN 0 响应：[count, key1, key2, ...]，key 从下标 1 开始
            for (int i = 1; i < root.size(); i++) {
                String key = asString(root.get(i));
                if (key != null && !key.isEmpty()) {
                    found.add(key);
                }
            }
            return found;
        });
        return keys == null ? new ArrayList<>() : keys;
    }

    // ==================== 解析与工具 ====================

    /**
     * 解析 FT.SEARCH 响应。兼容两种协议返回格式：
     * <ul>
     *   <li><b>RESP2 扁平</b>（NestedMultiOutput 产出）：count 在首位，命中按
     *       {@code [key, [字段...]]} 平铺；若带 WITHSCORES / KNN 附加分数则为
     *       {@code [key, [字段...], score]}；</li>
     *   <li><b>RESP3 JSON</b>（Lettuce 6 默认 RESP3，RediSearch 返回 map）：
     *       {@code [attributes, [], format, STRING, results, [命中...], total_results, N, warning, []]}，
     *       命中为 {@code [id, <key>, extra_attributes, [字段...], values, []]}。</li>
     * </ul>
     * KNN 查询的 {@code AS score} 别名会把距离以字段对（如 {@code "score", "0.35"}）
     * 追加到字段列表末尾，此处统一提取为 {@link Hit#getScore()}（COSINE 距离）。
     */
    private List<Hit> parseSearch(Object result) {
        List<Hit> hits = new ArrayList<>();
        if (!(result instanceof List)) {
            return hits;
        }
        List<?> root = (List<?>) result;
        if (root.size() < 2) {
            return hits;
        }
        if (isJsonFormat(root)) {
            parseJsonHits(root, hits);
        } else {
            parseFlatHits(root, hits);
        }
        return hits;
    }

    /** RESP3 JSON 格式以 "attributes" / "results" 键开头。 */
    private boolean isJsonFormat(List<?> root) {
        String firstKey = asString(root.get(0));
        return "attributes".equals(firstKey) || "results".equals(firstKey);
    }

    /** 解析 RESP3 JSON 格式：遍历顶层键值对，从 results 提取每条命中。 */
    private void parseJsonHits(List<?> root, List<Hit> hits) {
        for (int i = 0; i + 1 < root.size(); i += 2) {
            if (!"results".equals(asString(root.get(i))) || !(root.get(i + 1) instanceof List)) {
                continue;
            }
            for (Object hitObj : (List<?>) root.get(i + 1)) {
                if (!(hitObj instanceof List)) {
                    continue;
                }
                List<?> hit = (List<?>) hitObj;
                String id = null;
                List<?> fields = null;
                for (int j = 0; j + 1 < hit.size(); j += 2) {
                    String fieldKey = asString(hit.get(j));
                    Object fieldValue = hit.get(j + 1);
                    if ("id".equals(fieldKey)) {
                        id = asString(fieldValue);
                    } else if ("extra_attributes".equals(fieldKey) && fieldValue instanceof List) {
                        fields = (List<?>) fieldValue;
                    }
                }
                if (id != null) {
                    hits.add(toHit(id, fields));
                }
            }
            break;
        }
    }

    /** 解析 RESP2 扁平格式：count 在首位，命中按 [key, [字段...]]（或 +独立 score）平铺。 */
    private void parseFlatHits(List<?> root, List<Hit> hits) {
        long count = root.get(0) instanceof Number ? ((Number) root.get(0)).longValue() : 0;
        // 每条命中是否带独立 score 元素：响应总长度 = 1(count) + 命中数 * stride
        int stride = (count > 0 && root.size() - 1 == 3 * count) ? 3 : 2;
        for (int i = 1; i + 1 < root.size(); i += stride) {
            if (!(root.get(i + 1) instanceof List)) {
                continue;
            }
            String key = asString(root.get(i));
            Hit hit = toHit(key, (List<?>) root.get(i + 1));
            if (stride == 3 && i + 2 < root.size()) {
                // WITHSCORES / KNN 附加的独立 score 元素
                hit = new Hit(key, hit.getFields(), toDouble(root.get(i + 2), 0));
            }
            hits.add(hit);
        }
    }

    /** 由字段列表构造命中：字段两两成对，含 score 字段对时提取为 COSINE 距离。 */
    private Hit toHit(String key, List<?> flat) {
        Map<String, String> fields = new LinkedHashMap<>();
        double score = 0;
        if (flat != null) {
            for (int j = 0; j + 1 < flat.size(); j += 2) {
                fields.put(asString(flat.get(j)), asString(flat.get(j + 1)));
            }
            String named = fields.get("score");
            if (named != null) {
                try {
                    score = Double.parseDouble(named);
                } catch (NumberFormatException ignored) {
                    // 非数字分数按 0 处理
                }
            }
        }
        return new Hit(key, fields, score);
    }

    /** 执行原生命令回调（统一捕获异常，返回默认值）。 */
    private <T> T execute(RedisCallback<T> callback) {
        try {
            return redis.execute(callback);
        } catch (RuntimeException e) {
            log.warn("Redis 检索索引命令执行失败（索引为派生数据，可重建）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 执行原生命令，并用 {@link NestedMultiOutput}（ByteArrayCodec）解析响应，支持混合类型：
     * integer / 浮点 / 字符串 / 嵌套数组。
     * <p>
     * 不能走 {@link RedisConnection#execute(String, byte[]...)}：其底层用 ByteArrayOutput
     * 解析，遇到 FT.SEARCH 响应首元素的 integer（count）会抛 "does not support set(long)"
     * 而静默失败。这里改走 Lettuce 原生异步连接（{@link RedisAsyncCommands}）的
     * {@code dispatch(ProtocolKeyword, CommandOutput, CommandArgs)} 重载发送命令：
     * <ol>
     *   <li>经 {@link RedisConnection#getNativeConnection()}（接口方法，可穿透 Spring 的
     *       JDK 动态代理连接）获取底层 Lettuce 原生连接；</li>
     *   <li>用自定义 {@link ProtocolKeyword}（命令名）+ {@link CommandArgs} 组装命令；</li>
     *   <li>dispatch 返回 {@link RedisFuture}，{@code get()} 同步阻塞等待结果。</li>
     * </ol>
     *
     * @return 标量响应返回单个值（如 DEL 返回 Long、FT.CREATE 返回 "OK"）；
     *         数组响应返回原始 {@link List}（如 FT.SEARCH / FT.INFO）
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object executeCommand(RedisConnection connection, String command, List<Object> args) {
        byte[][] binaryArgs = toBytes(args);
        Object nativeConnection = connection.getNativeConnection();
        if (!(nativeConnection instanceof RedisAsyncCommands)) {
            log.warn("RedisSearchTemplate: 原生连接类型 {} 非 RedisAsyncCommands，命令 {} 走默认输出（无法解析混合响应）",
                    nativeConnection == null ? "null" : nativeConnection.getClass().getName(), command);
            return connection.execute(command, binaryArgs);
        }
        RedisAsyncCommands<byte[], byte[]> async = (RedisAsyncCommands<byte[], byte[]>) nativeConnection;
        NestedMultiOutput<byte[], byte[]> output = new NestedMultiOutput<>(ByteArrayCodec.INSTANCE);
        CommandArgs<byte[], byte[]> commandArgs = new CommandArgs<>(ByteArrayCodec.INSTANCE).addKeys(binaryArgs);
        RedisFuture<List<Object>> future = async.dispatch(new BytesKeyword(command), output, commandArgs);
        try {
            List<Object> raw = future.get();
            log.debug("RedisSearchTemplate: {} 响应类型={} 元素数={} 结构={}", command,
                    raw == null ? "null" : raw.getClass().getName(), raw == null ? 0 : raw.size(),
                    summarize(raw));
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            return raw.size() == 1 ? raw.get(0) : raw;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("命令 " + command + " 执行被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("命令 " + command + " 执行失败: " + e.getMessage(), e);
        }
    }

    /** 原生命令名（如 FT.SEARCH）的 {@link ProtocolKeyword} 实现。 */
    private static final class BytesKeyword implements ProtocolKeyword {
        private final String name;
        private final byte[] bytes;

        BytesKeyword(String name) {
            this.name = name;
            this.bytes = name.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public String name() {
            return name;
        }
    }

    /** 调试用：汇总响应结构（类型 + 取值截断），用于确认 FT.SEARCH 解析。 */
    private static String summarize(List<Object> raw) {
        return summarize(raw, 0);
    }

    private static String summarize(List<?> raw, int depth) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[");
        int limit = depth == 0 ? 12 : 6;
        for (int i = 0; i < raw.size() && i < limit; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object v = raw.get(i);
            sb.append(i).append(':').append(v == null ? "null" : v.getClass().getSimpleName());
            if (v instanceof byte[]) {
                String s = new String((byte[]) v, StandardCharsets.UTF_8);
                sb.append('=').append(s.length() > 40 ? s.substring(0, 40) + "…" : s);
            } else if (v instanceof List) {
                if (depth < 2) {
                    sb.append(summarize((List<?>) v, depth + 1));
                } else {
                    sb.append("(len=").append(((List<?>) v).size()).append(')');
                }
            } else {
                sb.append('=').append(v);
            }
        }
        if (raw.size() > limit) {
            sb.append(", …");
        }
        return sb.append(']').toString();
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(Object value) {
        if (value == null) {
            return new byte[0];
        }
        if (value instanceof byte[]) {
            return (byte[]) value;
        }
        return bytes(String.valueOf(value));
    }

    private static byte[][] toBytes(List<Object> values) {
        byte[][] result = new byte[values.size()][];
        for (int i = 0; i < values.size(); i++) {
            result[i] = bytes(values.get(i));
        }
        return result;
    }

    /** float4 小端字节序（RediSearch FLOAT32 编码）。 */
    public static byte[] toFloatBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(vector);
        return buffer.array();
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            return new String((byte[]) value, StandardCharsets.UTF_8);
        }
        return String.valueOf(value);
    }

    /** 兼容 Number / 字符串的分数解析，失败返回默认值。 */
    private static double toDouble(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        String raw = asString(value);
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 检索命中：key（条目 Hash 的 key）+ 字段 + 分数。
     */
    public static final class Hit {
        private final String key;
        private final Map<String, String> fields;
        private final double score;

        Hit(String key, Map<String, String> fields, double score) {
            this.key = key;
            this.fields = fields;
            this.score = score;
        }

        public String getKey() {
            return key;
        }

        public Map<String, String> getFields() {
            return fields;
        }

        public double getScore() {
            return score;
        }

        /** 取字段值（缺失返回 null）。 */
        public String field(String name) {
            return fields.get(name);
        }

        public double fieldDouble(String name, double defaultValue) {
            String value = fields.get(name);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        public int fieldInt(String name, int defaultValue) {
            String value = fields.get(name);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        public long fieldLong(String name, long defaultValue) {
            String value = fields.get(name);
            if (value == null) {
                return defaultValue;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
    }
}
