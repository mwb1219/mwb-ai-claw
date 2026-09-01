package com.mwb.ai.claw.domain.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 序列化 / 反序列化统一工具类。
 * <p>
 * 封装 Jackson，统一异常处理与 ObjectMapper 配置，供各模块复用，
 * 避免业务代码直接操作 {@link JsonNode} / {@link ObjectMapper} 导致可读性下降。
 *
 * @author mawenbin
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // 忽略目标类中不存在的字段，避免外部 JSON 多返回字段导致解析失败
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtils() {
    }

    /**
     * 对象序列化为 JSON 字符串。
     */
    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 序列化失败: " + obj.getClass().getSimpleName(), e);
        }
    }

    /**
     * JSON 字符串反序列化为指定类型对象。
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 反序列化失败: " + clazz.getSimpleName(), e);
        }
    }

    /**
     * JSON 数组字符串反序列化为 List。
     */
    public static <T> java.util.List<T> fromJsonList(String json, Class<T> elementClazz) {
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(java.util.List.class, elementClazz));
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 反序列化 List 失败: " + elementClazz.getSimpleName(), e);
        }
    }

    /**
     * JSON 字符串反序列化为泛型对象（如 List&lt;T&gt;、Map&lt;K,V&gt;）。
     */
    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        try {
            return MAPPER.readValue(json, typeRef);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 反序列化失败: " + typeRef.getType().getTypeName(), e);
        }
    }

    /**
     * 解析为 JsonNode（仅用于结构完全动态的场景，如 MCP JSON-RPC）。
     */
    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败", e);
        }
    }

    /**
     * 获取共享的 ObjectMapper（供需要原生能力的场景使用）。
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * 从 LLM 回复文本中容错提取 JSON（D2 结构化输出 / D3 产物解析）。
     * <p>
     * 容忍 markdown 代码围栏（```json ... ```）与前后缀文本；
     * 取首个 {@code {…}}（对象）或 {@code […]}(数组) 平衡括号块。
     *
     * @return 提取到的合法 JSON 文本；未找到返回 null
     */
    public static String extractJson(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        // 去除 markdown 代码围栏
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl >= 0) {
                t = t.substring(firstNl + 1);
            }
            int fence = t.lastIndexOf("```");
            if (fence >= 0) {
                t = t.substring(0, fence);
            }
            t = t.trim();
        }
        // 平衡括号扫描
        char open = 0;
        char close = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '{' || c == '[') {
                open = c;
                close = c == '{' ? '}' : ']';
                return scanBalanced(t, i, open, close);
            }
        }
        return null;
    }

    /** 从 start 起扫描到括号闭合，返回闭合后的子串（含括号）；不平衡返回 null */
    private static String scanBalanced(String t, int start, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    String json = t.substring(start, i + 1);
                    return validJson(json) ? json : null;
                }
            }
        }
        return null;
    }

    /** 校验提取片段是否为合法 JSON（提取失败返回 null 交给上层兜底） */
    private static boolean validJson(String json) {
        try {
            MAPPER.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
