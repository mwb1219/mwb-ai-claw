package com.mwb.ai.claw.infrastructure.config;

import com.alibaba.cola.exception.BizException;
import com.mwb.ai.claw.domain.collaboration.AgentOrchestrator;
import com.mwb.ai.claw.domain.collaboration.OrchestrationDefinition;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.infrastructure.collaboration.OrchestratorRegistry;
import com.mwb.ai.claw.infrastructure.util.ConfigFileLocator;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 编排注册表加载器：加载 orchestrations.json（编排定义 + 意图元数据）。
 * <p>
 * 读取优先级：运行目录及其上级目录 ./orchestrations.json 优先，回退 classpath 默认模板。
 * 首次加载时执行启动校验：id 唯一、type 已注册、引用的 agentId 存在。
 */
@Component
public class OrchestrationConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationConfigLoader.class);

    private static final String FILE_NAME = "orchestrations.json";

    private final OrchestratorRegistry orchestratorRegistry;
    private final AgentRegistryLoader agentRegistryLoader;
    private final AgentProperties agentProperties;

    private volatile Map<String, OrchestrationDefinition> cached;

    public OrchestrationConfigLoader(OrchestratorRegistry orchestratorRegistry,
                                     AgentRegistryLoader agentRegistryLoader,
                                     AgentProperties agentProperties) {
        this.orchestratorRegistry = orchestratorRegistry;
        this.agentRegistryLoader = agentRegistryLoader;
        this.agentProperties = agentProperties;
    }

    /**
     * 加载全部编排定义（懒加载并缓存，首次加载时校验）。
     */
    public List<OrchestrationDefinition> loadDefinitions() {
        return new ArrayList<>(loadIndexed().values());
    }

    /**
     * 按 id 取编排定义；不存在抛业务异常并列出可用 id。
     */
    public OrchestrationDefinition get(String id) {
        OrchestrationDefinition definition = loadIndexed().get(id);
        if (definition == null) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "编排不存在: " + id + "（可用编排: " + loadIndexed().keySet() + "）");
        }
        return definition;
    }

    private Map<String, OrchestrationDefinition> loadIndexed() {
        if (cached == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = doLoad();
                }
            }
        }
        return cached;
    }

    private Map<String, OrchestrationDefinition> doLoad() {
        String json = ConfigFileLocator.readConfigFile(FILE_NAME);
        if (json == null || json.trim().isEmpty()) {
            log.warn("未找到 {}，使用内置默认编排（routing）", FILE_NAME);
            Map<String, OrchestrationDefinition> fallback = new LinkedHashMap<>();
            OrchestrationDefinition routing = new OrchestrationDefinition();
            routing.setId("routing");
            routing.setType("routing");
            fallback.put(routing.getId(), routing);
            return fallback;
        }

        OrchestrationsFile file = JsonUtils.fromJson(json, OrchestrationsFile.class);
        if (file == null || file.getOrchestrations() == null || file.getOrchestrations().isEmpty()) {
            throw new IllegalArgumentException(FILE_NAME + " 内容为空或格式错误");
        }

        Map<String, OrchestrationDefinition> indexed = new LinkedHashMap<>();
        Set<String> knownAgentIds = collectAgentIds();
        for (OrchestrationDefinition definition : file.getOrchestrations()) {
            if (definition.getId() == null || definition.getId().trim().isEmpty()) {
                throw new IllegalArgumentException(FILE_NAME + " 中存在缺少 id 的编排定义");
            }
            if (indexed.containsKey(definition.getId())) {
                throw new IllegalArgumentException("编排 id 重复: " + definition.getId());
            }
            validate(definition, knownAgentIds);
            indexed.put(definition.getId(), definition);
        }
        log.info("已加载编排注册表（orchestrations.json）：{}", indexed.keySet());
        return indexed;
    }

    /** 启动校验：type 已注册、引用 agentId 存在 */
    private void validate(OrchestrationDefinition definition, Set<String> knownAgentIds) {
        AgentOrchestrator plugin = orchestratorRegistry.get(definition.getType());
        if (plugin == null) {
            throw new IllegalArgumentException("编排 '" + definition.getId()
                    + "' 引用了未注册的类型 '" + definition.getType() + "'");
        }
        plugin.validate(definition);
        if (definition.getAgents() != null) {
            for (String agentId : definition.getAgents()) {
                if (!knownAgentIds.contains(agentId)) {
                    throw new IllegalArgumentException("编排 '" + definition.getId()
                            + "' 引用了不存在的 Agent: " + agentId);
                }
            }
        }
    }

    private Set<String> collectAgentIds() {
        Set<String> ids = new HashSet<>();
        ids.add(agentProperties.getAgentId());
        ids.addAll(agentRegistryLoader.loadAgents().stream()
                .map(AgentProperties.AgentConfig::getAgentId)
                .collect(Collectors.toSet()));
        return ids;
    }
}
