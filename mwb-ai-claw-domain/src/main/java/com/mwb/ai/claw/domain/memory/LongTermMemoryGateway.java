package com.mwb.ai.claw.domain.memory;

import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 长期记忆网关接口：文件级持久化记忆（AGENT.md / MEMORY.md）。
 * <p>
 * 提供跨会话的持久记忆能力。AGENT.md 在 Agent 加载时合并到 system prompt；
 * MEMORY.md 可通过 read_memory / write_memory 工具在运行时读写。
 * <p>
 * 与 {@link MemoryStrategy}（会话级记忆）互补，共同构成记忆子系统。
 */
public interface LongTermMemoryGateway {

    /**
     * 加载 AGENT.md 内容（Agent 扩展指令，在 systemPrompt 之后追加）。
     *
     * @return AGENT.md 文件内容，不存在时返回空字符串
     */
    String loadAgentInstructions(AgentScope scope);

    /**
     * 加载 MEMORY.md 内容（长期记忆，在 system prompt 末尾注入供 Agent 参考）。
     *
     * @return MEMORY.md 文件内容，不存在时返回空字符串
     */
    String loadMemory(AgentScope scope);

    /**
     * 保存 MEMORY.md 内容（覆盖写入，由调用方自行做合并去重）。
     *
     * @param content 新的长期记忆内容
     */
    void saveMemory(AgentScope scope, String content);

    /**
     * 保存 AGENT.md 内容（T9：租户级 Agent 行为规则，覆盖写入；业务方运行时更新无需重启）。
     * <p>
     * 默认空实现（不修改 AGENT.md）：为 SPI 扩展点，自定义网关需显式实现。
     *
     * @param content 新的 Agent 行为规则内容
     */
    default void saveAgentInstructions(AgentScope scope, String content) {
        // 默认不落地，避免破坏既有自定义实现；JDBC / 文件实现均覆盖此方法
    }
}
