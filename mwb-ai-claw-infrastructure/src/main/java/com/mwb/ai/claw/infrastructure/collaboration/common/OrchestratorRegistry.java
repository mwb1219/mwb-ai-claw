package com.mwb.ai.claw.infrastructure.collaboration.common;

import com.mwb.ai.claw.domain.collaboration.model.OrchestrationDefinition;
import com.mwb.ai.claw.domain.collaboration.spi.AgentOrchestrator;
import com.mwb.ai.claw.domain.collaboration.spi.OrchestratorResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排插件注册中心：启动期收集所有 AgentOrchestrator 插件，按 type() 建索引。
 * <p>
 * 新增编排插件仅需：实现 AgentOrchestrator + 注册为 Spring Bean，无需修改本类。
 */
@Component
public class OrchestratorRegistry implements OrchestratorResolver {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorRegistry.class);

    private final Map<String, AgentOrchestrator> orchestrators = new HashMap<>();

    public OrchestratorRegistry(List<AgentOrchestrator> plugins) {
        for (AgentOrchestrator plugin : plugins) {
            String type = plugin.type();
            if (orchestrators.putIfAbsent(type, plugin) != null) {
                throw new IllegalStateException("编排类型重复注册: " + type);
            }
        }
        log.info("已注册编排插件: {}", orchestrators.keySet());
    }

    /**
     * 按类型取编排插件；不存在返回 null。
     */
    public AgentOrchestrator get(String type) {
        return orchestrators.get(type);
    }

    /**
     * 解析编排定义对应的插件并执行配置校验。
     *
     * @throws IllegalArgumentException 编排类型未注册或配置校验失败
     */
    @Override
    public AgentOrchestrator resolve(OrchestrationDefinition definition) {
        AgentOrchestrator orchestrator = orchestrators.get(definition.getType());
        if (orchestrator == null) {
            throw new IllegalArgumentException("未知编排类型: " + definition.getType()
                    + "（可用编排插件: " + orchestrators.keySet() + "）");
        }
        orchestrator.validate(definition);
        return orchestrator;
    }
}
