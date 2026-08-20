package com.mwb.ai.claw.infrastructure.llm;

/**
 * 单次运行 token 预算：在当前线程内累计 prompt + completion 消耗，超限即拒绝后续 LLM 调用。
 * <p>
 * 通过 {@link #bind} / {@link #unbind} 在当前运行（一次 chat 执行）的线程内绑定，
 * 由 {@link ResilientLlmGateway} 在每次 LLM 调用成功后消费；多 Agent 协作在子线程执行时
 * 预算不继承（主线程的直接 LLM 调用受控）。
 */
public class RunTokenBudget {

    private static final ThreadLocal<RunTokenBudget> HOLDER = new ThreadLocal<>();

    /** 预算上限（0 = 不限） */
    private final long limit;

    /** 已消耗 token 数 */
    private long consumed;

    public RunTokenBudget(long limit) {
        this.limit = Math.max(0, limit);
    }

    public static RunTokenBudget bind(long limit) {
        RunTokenBudget budget = new RunTokenBudget(limit);
        HOLDER.set(budget);
        return budget;
    }

    public static RunTokenBudget current() {
        return HOLDER.get();
    }

    public static void unbind() {
        HOLDER.remove();
    }

    public boolean isUnlimited() {
        return limit <= 0;
    }

    public long getLimit() {
        return limit;
    }

    public long getConsumed() {
        return consumed;
    }

    /**
     * 尝试消耗 tokens：未超限则累加并返回 true；超限则不动 consumed 并返回 false。
     */
    public boolean tryConsume(long tokens) {
        if (isUnlimited()) {
            return true;
        }
        if (tokens <= 0) {
            return true;
        }
        if (consumed + tokens > limit) {
            return false;
        }
        consumed += tokens;
        return true;
    }
}
