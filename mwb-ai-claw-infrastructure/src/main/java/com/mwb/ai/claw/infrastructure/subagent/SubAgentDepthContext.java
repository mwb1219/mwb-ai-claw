package com.mwb.ai.claw.infrastructure.subagent;

/**
 * 子代理嵌套深度 ThreadLocal 上下文（H3 · Agent-as-Tool，切片 2）。
 * <p>
 * 用于 {@code max-descendants} 递归配额管控：主 Agent 深度为 0，每 spawn 一层深度 +1，
 * 由 {@link SpawnedAgentRegistry} 对应的异步任务（或同步 spawn）在子线程内设置/清理。
 * 默认 0（请求主线程），未设置时按「主 Agent 视角」放行第一层 spawn。
 * <p>
 * 之所以单独建立而非复用 {@code AgentScopeContext} 或 {@code RunTokenBudget}：
 * 深度是「调用链」维度而非「身份/预算」维度，隔离更纯粹、便于在子线程显式写入。
 */
public final class SubAgentDepthContext {

    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

    private SubAgentDepthContext() {
    }

    /** 当前调用链的子代理嵌套深度（未设置视为主 Agent，深度 0） */
    public static int current() {
        Integer depth = DEPTH.get();
        return depth == null ? 0 : depth;
    }

    /** 设置当前线程的嵌套深度（spawn 子线程进入时调用） */
    public static void set(int depth) {
        DEPTH.set(depth);
    }

    /** 清理当前线程的嵌套深度（spawn 子线程 finally 调用，防 ThreadLocal 泄漏） */
    public static void clear() {
        DEPTH.remove();
    }
}
