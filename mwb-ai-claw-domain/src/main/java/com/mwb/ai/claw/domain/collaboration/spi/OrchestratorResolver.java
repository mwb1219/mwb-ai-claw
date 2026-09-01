package com.mwb.ai.claw.domain.collaboration.spi;

import com.mwb.ai.claw.domain.collaboration.model.OrchestrationDefinition;

/**
 * 编排器解析器 SPI：根据编排定义解析对应的 AgentOrchestrator 插件。
 * <p>
 * 实现由 infrastructure 层提供：{@code OrchestratorRegistry} 维护插件注册表。
 */
public interface OrchestratorResolver {

    /**
     * 解析编排定义对应的插件并执行配置校验。
     *
     * @throws IllegalArgumentException 编排类型未注册或配置校验失败
     */
    AgentOrchestrator resolve(OrchestrationDefinition definition);
}
