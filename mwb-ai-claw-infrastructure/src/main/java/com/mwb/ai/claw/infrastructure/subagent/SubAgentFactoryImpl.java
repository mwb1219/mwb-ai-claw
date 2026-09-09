package com.mwb.ai.claw.infrastructure.subagent;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.subagent.SubAgentFactory;
import com.mwb.ai.claw.domain.subagent.SubAgentSpec;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 动态子代理构建默认实现（H3 · Agent-as-Tool）。
 * <p>
 * 从 {@link AgentGateway} 取默认 Agent 作为继承基座，再按 {@link SubAgentSpec} 覆盖差异字段：
 * <ul>
 *   <li>{@code agentId} 用 {@code sub-{uuid}}，不写入 {@code agents.json}、不污染全局注册表；</li>
 *   <li>{@code systemPrompt} = 基座 systemPrompt + spec.instructions（继承框架人设与安全约束，叠加专属指令）；</li>
 *   <li>{@code modelConfig}：spec.model/provider/maxTokens 有值则覆盖，缺省继承基座；</li>
 *   <li>{@code toolNames}：spec.tools 空 = 绑定全部（缺省语义），非空 = 强制仅绑定声明工具；</li>
 *   <li>{@code maxSteps}：spec.maxSteps 非正数时继承基座默认。</li>
 * </ul>
 * 本实现不依赖 Spring 注解（由 {@code ClawCoreAutoConfiguration} 以 {@code @ConditionalOnMissingBean} 注册，使用方可替换）。
 */
public class SubAgentFactoryImpl implements SubAgentFactory {

    private final AgentGateway agentGateway;

    private final AgentProperties agentProperties;

    public SubAgentFactoryImpl(AgentGateway agentGateway, AgentProperties agentProperties) {
        this.agentGateway = agentGateway;
        this.agentProperties = agentProperties;
    }

    @Override
    public Agent create(SubAgentSpec spec, AgentScope scope) {
        Agent base = agentGateway.getAgent(null); // 缺省：返回默认 Agent（框架统一人设 / 模型 / 安全约束）
        Agent sub = new Agent();
        sub.setAgentId("sub-" + UUID.randomUUID()); // 临时 id，不入 agents.json
        sub.setName(spec.getName() == null || spec.getName().trim().isEmpty() ? "sub-agent" : spec.getName());
        sub.setSystemPrompt(mergePrompt(base.getSystemPrompt(), spec.getInstructions()));
        sub.setDescription(spec.getName() == null || spec.getName().trim().isEmpty()
                ? "runtime sub-agent" : spec.getName());
        sub.setAgentInstructions(base.getAgentInstructions()); // 继承 AGENT.md 扩展（可选）
        sub.setModelConfig(copyOrDefault(base.getModelConfig(), spec));
        List<String> tools = spec.getTools();
        sub.setToolNames(tools == null || tools.isEmpty() ? new ArrayList<>() : new ArrayList<>(tools));
        sub.setMaxSteps(spec.getMaxSteps() > 0 ? spec.getMaxSteps() : base.getMaxSteps());
        return sub;
    }

    @Override
    public void validate(SubAgentSpec spec) {
        if (spec == null || spec.getTask() == null || spec.getTask().trim().isEmpty()) {
            throw new IllegalArgumentException("SubAgentSpec.task 不能为空");
        }
        if (spec.getMaxSteps() < 0) {
            throw new IllegalArgumentException("SubAgentSpec.maxSteps 不能为负数: " + spec.getMaxSteps());
        }
        if (spec.getMaxTokens() < 0) {
            throw new IllegalArgumentException("SubAgentSpec.maxTokens 不能为负数: " + spec.getMaxTokens());
        }
    }

    @Override
    public boolean isAllowed(AgentScope scope) {
        AgentProperties.SubAgentConfig cfg = agentProperties.getSubagent();
        if (!cfg.isEnabled()) {
            return false;
        }
        List<String> allowedTenants = cfg.getAllowedTenants();
        if (allowedTenants == null || allowedTenants.isEmpty()) {
            return true; // 未配置白名单 = 全部租户允许
        }
        if (scope == null || scope.getTenantId() == null) {
            return false; // 非租户化 scope 不在白名单内
        }
        return allowedTenants.contains(scope.getTenantId());
    }

    /** 基座 systemPrompt + spec.instructions（追加专属人设，空则不追加） */
    private String mergePrompt(String basePrompt, String extraInstructions) {
        if (extraInstructions == null || extraInstructions.trim().isEmpty()) {
            return basePrompt;
        }
        if (basePrompt == null || basePrompt.isEmpty()) {
            return extraInstructions;
        }
        return basePrompt + "\n\n" + extraInstructions;
    }

    /** 复制基座 ModelConfig，再按 spec 覆盖 model / provider / maxTokens（空 / 非正数则不覆盖） */
    private ModelConfig copyOrDefault(ModelConfig base, SubAgentSpec spec) {
        ModelConfig mc = new ModelConfig();
        if (base != null) {
            mc.setModel(base.getModel());
            mc.setProvider(base.getProvider());
            mc.setBaseUrl(base.getBaseUrl());
            mc.setApiKey(base.getApiKey());
            mc.setTemperature(base.getTemperature());
            mc.setMaxTokens(base.getMaxTokens());
            mc.setThinking(base.getThinking());
        }
        if (spec.getModel() != null && !spec.getModel().trim().isEmpty()) {
            mc.setModel(spec.getModel());
        }
        if (spec.getProvider() != null && !spec.getProvider().trim().isEmpty()) {
            mc.setProvider(spec.getProvider());
        }
        if (spec.getMaxTokens() > 0) {
            mc.setMaxTokens(spec.getMaxTokens());
        }
        return mc;
    }
}
