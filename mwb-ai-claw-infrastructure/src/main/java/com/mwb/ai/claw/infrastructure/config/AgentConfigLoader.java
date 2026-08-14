package com.mwb.ai.claw.infrastructure.config;

import com.mwb.ai.claw.infrastructure.util.ConfigFileLocator;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 配置加载器：根据协作模式（agent.mode）加载对应的 {mode}-agents.json。
 * <p>
 * 读取优先级：运行目录及其上级目录 ./{mode}-agents.json 优先，回退 classpath 默认模板。
 * 文件中的 ${VAR:default} 占位符通过 Spring Environment 解析（.env / 系统环境变量）。
 */
@Component
public class AgentConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigLoader.class);

    private final AgentProperties agentProperties;
    private final Environment environment;

    private volatile List<AgentProperties.AgentConfig> cached;

    public AgentConfigLoader(AgentProperties agentProperties, Environment environment) {
        this.agentProperties = agentProperties;
        this.environment = environment;
    }

    /**
     * 加载当前协作模式下的 Agent 定义（懒加载并缓存）。
     */
    public List<AgentProperties.AgentConfig> loadAgents() {
        if (cached == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = doLoad();
                }
            }
        }
        return cached;
    }

    private List<AgentProperties.AgentConfig> doLoad() {
        String mode = agentProperties.getMode();
        if (mode == null || mode.trim().isEmpty()) {
            mode = "routing";
        }
        String fileName = mode + "-agents.json";
        String json = readConfig(fileName);
        if (json == null || json.trim().isEmpty()) {
            log.warn("未找到 {}，Agent 列表为空", fileName);
            return new ArrayList<>();
        }

        AgentsFile file = JsonUtils.fromJson(json, AgentsFile.class);
        if (file == null || file.getAgents() == null) {
            log.warn("{} 内容为空或格式错误", fileName);
            return new ArrayList<>();
        }

        for (AgentProperties.AgentConfig config : file.getAgents()) {
            resolvePlaceholders(config);
        }
        log.info("已加载协作模式 '{}' 的 {} 个 Agent", file.getMode() != null ? file.getMode() : mode, file.getAgents().size());
        return file.getAgents();
    }

    private String readConfig(String fileName) {
        return ConfigFileLocator.readConfigFile(fileName);
    }

    private void resolvePlaceholders(AgentProperties.AgentConfig config) {
        if (config.getModel() != null) {
            config.setModel(resolveWithDefault(config.getModel()));
        }
        if (config.getBaseUrl() != null) {
            config.setBaseUrl(resolveWithDefault(config.getBaseUrl()));
        }
        if (config.getApiKey() != null) {
            config.setApiKey(resolveWithDefault(config.getApiKey()));
        }
    }

    /**
     * 解析占位符（如 ${CODER_API_KEY:${DEFAULT_API_KEY:}}）。
     * <p>
     * Spring 的占位符默认值只在属性不存在时生效；若 .env 中显式写了空值（如
     * CODER_API_KEY=），解析结果会是空串而非默认值。这里在结果为空串时提取
     * 占位符默认值部分重新解析，使「留空则继承默认」的语义生效。
     */
    private String resolveWithDefault(String raw) {
        if (raw == null) {
            return null;
        }
        String resolved = environment.resolvePlaceholders(raw);
        if (!resolved.isEmpty()) {
            return resolved;
        }
        // 形如 ${KEY:default}：取顶层第一个 : 之后的默认部分（可能仍含嵌套占位符）重新解析
        if (raw.startsWith("${") && raw.endsWith("}")) {
            String inner = raw.substring(2, raw.length() - 1);
            int sep = inner.indexOf(':');
            if (sep > 0) {
                return environment.resolvePlaceholders(inner.substring(sep + 1));
            }
        }
        return resolved;
    }
}
