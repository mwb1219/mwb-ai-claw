package com.mwb.ai.claw.infrastructure.util;

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
}
