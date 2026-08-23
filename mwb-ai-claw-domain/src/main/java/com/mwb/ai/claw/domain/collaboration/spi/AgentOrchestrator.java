package com.mwb.ai.claw.domain.collaboration.spi;

import com.mwb.ai.claw.domain.collaboration.model.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.model.OrchestrationDefinition;

/**
 * 编排插件 SPI：编排方式的可插拔实现。
 * <p>
 * 实现类通过 {@link #type()} 声明自己的编排类型标识（如 routing / conversational / delegate），
 * 由注册中心在启动期收集注册。新增编排方式无需改动主链路，仅需：
 * 1. 实现本接口并注册为 Spring Bean；
 * 2. 在 orchestrations.json 增加一条 {@link OrchestrationDefinition}（type 指向该类型）。
 */
public interface AgentOrchestrator {

    /**
     * 编排类型标识（全局唯一，与 OrchestrationDefinition.type 匹配）。
     */
    String type();

    /**
     * 编排配置校验（启动期执行），配置不合法应抛异常。
     */
    default void validate(OrchestrationDefinition definition) {
    }

    /**
     * 执行一次协作编排。
     */
    CollaborationResult orchestrate(OrchestrationContext context);
}
