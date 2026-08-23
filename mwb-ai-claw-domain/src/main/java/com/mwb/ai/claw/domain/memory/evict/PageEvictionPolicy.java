package com.mwb.ai.claw.domain.memory.evict;

import com.mwb.ai.claw.domain.memory.model.EvictionContext;

/**
 * 换页策略接口（可插拔）：决定何时把最旧的历史消息块压缩为摘要页。
 * <p>
 * 内置实现：
 * <ul>
 *   <li>token（默认）：预算驱动 —— 消息总量超预算或未摘要消息过多即触发；</li>
 *   <li>importance：重要度驱动 —— 低价值消息块提前压缩，高价值消息尽量留在工作记忆。</li>
 * </ul>
 */
public interface PageEvictionPolicy {

    /**
     * 判断当前是否应触发换页（压缩最旧未摘要块）。
     *
     * @param context 换页上下文（消息/预算/边界等快照）
     * @return true 表示应触发
     */
    boolean shouldEvict(EvictionContext context);
}
