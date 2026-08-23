package com.mwb.ai.claw.domain.rag.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单次 Agent 请求选中的知识库列表。
 */
public final class RagRequestContext {

    /** 当前请求线程的 RAG 状态。 */
    private static final ThreadLocal<State> HOLDER = new ThreadLocal<>();

    private RagRequestContext() {
    }

    /**
     * 绑定当前请求选中的知识库列表。
     *
     * @param knowledgeBaseIds 知识库列表，可为空
     */
    public static void bind(List<String> knowledgeBaseIds) {
        List<String> ids = knowledgeBaseIds == null
                ? Collections.emptyList() : new ArrayList<>(knowledgeBaseIds);
        HOLDER.set(new State(Collections.unmodifiableList(ids)));
    }

    /**
     * 返回当前请求绑定的知识库列表。
     *
     * @return 不可变列表；未绑定时返回 {@code null}
     */
    public static List<String> knowledgeBaseIds() {
        State state = HOLDER.get();
        return state == null ? null : state.knowledgeBaseIds;
    }

    /**
     * 获取指定查询已缓存的上下文。
     *
     * @param query 查询文本
     * @return 缓存的上下文；未缓存或未绑定时返回 {@code null}
     */
    public static String cachedContext(String query) {
        State state = HOLDER.get();
        return state == null ? null : state.contextCache.get(query);
    }

    /**
     * 缓存指定查询的上下文，避免同一请求内重复检索。
     *
     * @param query   查询文本
     * @param context 检索结果上下文
     */
    public static void cacheContext(String query, String context) {
        State state = HOLDER.get();
        if (state != null && query != null && context != null) {
            state.contextCache.put(query, context);
        }
    }

    /** 清除当前线程的 RAG 请求状态。 */
    public static void unbind() {
        HOLDER.remove();
    }

    /**
     * 捕获当前 RAG 请求上下文，供编排器提交到工作线程时显式传播。
     */
    public static Runnable wrap(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为空");
        }
        State captured = HOLDER.get();
        if (captured == null) {
            return task;
        }
        return () -> {
            State previous = HOLDER.get();
            HOLDER.set(captured);
            try {
                task.run();
            } finally {
                if (previous == null) {
                    HOLDER.remove();
                } else {
                    HOLDER.set(previous);
                }
            }
        };
    }

    /**
     * 捕获当前 RAG 请求上下文，供有返回值的异步任务显式传播。
     */
    public static <T> Callable<T> wrapCallable(Callable<T> task) {
        if (task == null) {
            throw new IllegalArgumentException("task 不能为空");
        }
        State captured = HOLDER.get();
        if (captured == null) {
            return task;
        }
        return () -> {
            State previous = HOLDER.get();
            HOLDER.set(captured);
            try {
                return task.call();
            } finally {
                if (previous == null) {
                    HOLDER.remove();
                } else {
                    HOLDER.set(previous);
                }
            }
        };
    }

    private static final class State {
        /** 当前请求选中的知识库列表（不可变）。 */
        private final List<String> knowledgeBaseIds;
        /** 查询文本到上下文内容的缓存。 */
        private final Map<String, String> contextCache = new ConcurrentHashMap<>();

        private State(List<String> knowledgeBaseIds) {
            this.knowledgeBaseIds = knowledgeBaseIds;
        }
    }
}
