package com.mwb.ai.claw.domain.memory;

/**
 * 长期记忆网关接口：文件级持久化记忆（AGENT.md / MEMORY.md）。
 * <p>
 * 提供跨会话的持久记忆能力。AGENT.md 在 Agent 加载时合并到 system prompt；
 * MEMORY.md 可通过 read_memory / write_memory 工具在运行时读写。
 * <p>
 * 与 {@link MemoryGateway}（会话级记忆）互补，共同构成记忆子系统。
 */
public interface LongTermMemoryGateway {

    /**
     * 加载 AGENT.md 内容（Agent 扩展指令，在 systemPrompt 之后追加）。
     *
     * @return AGENT.md 文件内容，不存在时返回空字符串
     */
    String loadAgentInstructions();

    /**
     * 加载 MEMORY.md 内容（长期记忆，在 system prompt 末尾注入供 Agent 参考）。
     *
     * @return MEMORY.md 文件内容，不存在时返回空字符串
     */
    String loadMemory();

    /**
     * 保存 MEMORY.md 内容（覆盖写入）。
     *
     * @param content 新的长期记忆内容
     */
    void saveMemory(String content);
}
