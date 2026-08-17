package com.mwb.ai.claw.domain.collaboration;

import java.util.List;

/**
 * 编排选择器 SPI：根据用户消息意图选择编排。
 * <p>
 * 与 {@code AgentRouter}（消息 → Agent）不同，本选择器目标是「消息 → 编排」：
 * 先选编排，再由编排内部（如 routing 插件）决定具体 Agent，两层决策职责分离。
 */
public interface OrchestrationSelector {

    /**
     * 意图 → 编排 id。
     *
     * @param message     用户消息
     * @param definitions 全部编排定义
     * @return 匹配的编排 id；无法判断返回 null（由调用方回退默认编排）
     */
    String select(String message, List<OrchestrationDefinition> definitions);
}
