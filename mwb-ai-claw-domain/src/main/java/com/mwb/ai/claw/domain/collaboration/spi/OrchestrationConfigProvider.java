package com.mwb.ai.claw.domain.collaboration.spi;

import com.mwb.ai.claw.domain.collaboration.model.OrchestrationDefinition;

/**
 * 编排定义提供者 SPI：按 id 提供编排定义。
 * <p>
 * 实现由 infrastructure 层提供：{@code OrchestrationConfigLoader} 加载 orchestrations.json。
 */
public interface OrchestrationConfigProvider {

    /**
     * 按 id 获取编排定义；不存在返回 null。
     */
    OrchestrationDefinition get(String orchestrationId);
}
