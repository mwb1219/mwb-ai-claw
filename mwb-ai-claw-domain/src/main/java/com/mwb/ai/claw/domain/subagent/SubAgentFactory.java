package com.mwb.ai.claw.domain.subagent;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 动态子代理构建 SPI（H3 · Agent-as-Tool）。
 * <p>
 * 给定 {@link SubAgentSpec} 与当前调用方 {@link AgentScope}，构建出一个可执行的临时 {@link Agent} 实例，
 * 供 {@code spawn_agent} 工具交给 {@code ExecutionUnit.runAgentResult} 在临时会话中执行（不入库、不注册到网关）。
 * <p>
 * 这是一个「构建 Agent」的纯函数，不耦合工具执行；显式接收 scope 做多租户隔离，也便于并行 / prepare 阶段构造。
 */
public interface SubAgentFactory {

    /**
     * 根据规格与 scope 构建可执行的 Agent 实例（不落库、不注册到 Agent 网关）。
     */
    Agent create(SubAgentSpec spec, AgentScope scope);

    /**
     * 校验子代理规格，非法即抛（如模型 / 工具 / 步数越界）；启动或调用前 fail-fast。
     */
    default void validate(SubAgentSpec spec) {
    }

    /**
     * 是否允许该 scope 生成子代理（租户级开关 / 配额，防止滥用）。
     */
    default boolean isAllowed(AgentScope scope) {
        return true;
    }
}
