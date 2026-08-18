package com.mwb.ai.claw.infrastructure.collaboration.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.alibaba.cola.exception.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mwb.ai.claw.domain.collaboration.AgentOrchestrator;
import com.mwb.ai.claw.domain.collaboration.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.OrchestrationDefinition;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.infrastructure.collaboration.PipelineStage;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * 流水线编排（内置插件，type=pipeline）：
 * 按预定义阶段（stages）顺序接力，前一阶段产物作为后一阶段输入。
 * 阶段独立临时会话执行（上下文隔离），产物支持 text / file 两种传递方式，失败支持 abort / continue。
 */
@Component
public class PipelineOrchestrator implements AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private static final String DEFAULT_WORKDIR = "orchestration-artifacts";

    @Resource
    private AgentGateway agentGateway;

    @Override
    public String type() {
        return "pipeline";
    }

    @Override
    public void validate(OrchestrationDefinition definition) {
        List<PipelineStage> stages = parseStages(definition);
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException("流水线编排 '" + definition.getId() + "' 缺少 stages 配置");
        }
        Set<String> knownIds = agentGateway.listAgents().stream()
                .map(Agent::getAgentId).collect(Collectors.toSet());
        for (PipelineStage stage : stages) {
            String agentId = stage.getAgentId();
            if (agentId == null || agentId.trim().isEmpty()) {
                throw new IllegalArgumentException("流水线编排 '" + definition.getId() + "' 存在缺少 agentId 的阶段");
            }
            if (!knownIds.contains(agentId)) {
                throw new IllegalArgumentException("流水线编排 '" + definition.getId()
                        + "' 阶段引用了不存在的 Agent: " + agentId);
            }
            if (stage.getPromptTemplate() == null || stage.getPromptTemplate().trim().isEmpty()) {
                throw new IllegalArgumentException("流水线编排 '" + definition.getId() + "' 阶段缺少 promptTemplate: " + agentId);
            }
        }
    }

    @Override
    public CollaborationResult orchestrate(OrchestrationContext ctx) {
        List<PipelineStage> stages = stages(ctx.getDefinition());
        String workdir = workdir(ctx.getDefinition());

        String input = ctx.getMessage();
        String reply = null;
        String lastAgentId = null;
        List<String> trace = new ArrayList<>();

        if (ctx.getCallback() != null) {
            ctx.getCallback().onProgress("[Orchestration] 流水线开始: " + stages.size() + " 个阶段");
        }

        for (PipelineStage stage : stages) {
            String stageId = stage.getStageId();
            String agentId = stage.getAgentId();
            String template = stage.getPromptTemplate();
            boolean abortOnFailure = stage.abortOnFailure();

            Agent agent = agentGateway.getAgent(agentId);
            // 阶段级思考模式覆盖：默认直接输出类阶段建议 thinking=false，
            // 避免推理 token 吃满输出预算导致 content 为空（如 DeepSeek 思考模式）
            if (stage.getThinking() != null) {
                agent.getModelConfig().setThinking(stage.getThinking());
            }
            String prompt = renderTemplate(template, input);

            String stageReply = runStage(ctx, prompt, agent, stageId);
            // 空回复容错：LLM 输出被截断等导致空回复时，重试一次并要求直接输出
            if (stageReply == null || stageReply.trim().isEmpty()) {
                log.warn("流水线阶段 {} 回复为空，重试一次", stageId);
                String retryPrompt = prompt + "\n\n（注意：你的上一条回复为空。请直接输出完整回答，不要调用任何工具，不要留空。）";
                stageReply = runStage(ctx, retryPrompt, agent, stageId);
            }

            if (stageReply == null || stageReply.trim().isEmpty()) {
                if (abortOnFailure) {
                    throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                            "流水线阶段无产出已终止: " + stageId);
                }
                continue;
            }

            String step = "[Stage:" + stageId + "] " + agent.getName() + ": " + truncate(stageReply);
            trace.add(step);
            if (ctx.getCallback() != null) {
                ctx.getCallback().onProgress(step);
            }

            reply = stageReply;
            lastAgentId = agentId;
            // 产物传递：text → 直接作为下一阶段输入；file → 落盘并传递文件路径
            input = stage.isFilePass()
                    ? ctx.getExecutionUnit().writeArtifact(workdir, stageId, stageReply).toString()
                    : stageReply;
        }

        if (reply == null) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "流水线所有阶段均未产出结果");
        }

        CollaborationResult cr = new CollaborationResult();
        cr.setReply(reply);
        cr.setAgentId(lastAgentId);
        cr.setSessionId(ctx.getSessionId());
        cr.setOrchestrationId(ctx.getDefinition().getId());
        cr.setTraceSteps(trace);
        return cr;
    }

    /** 解析阶段定义（缺少 stages 时返回 null，由调用方决定异常语义） */
    private List<PipelineStage> parseStages(OrchestrationDefinition definition) {
        Object raw = definition.getConfig().get("stages");
        if (raw == null) {
            return null;
        }
        return JsonUtils.mapper().convertValue(raw, new TypeReference<List<PipelineStage>>() {});
    }

    /** 解析阶段定义（缺少 stages 时抛业务异常，供执行路径使用） */
    private List<PipelineStage> stages(OrchestrationDefinition definition) {
        List<PipelineStage> stages = parseStages(definition);
        if (stages == null || stages.isEmpty()) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "流水线编排缺少 stages 配置: " + definition.getId());
        }
        return stages;
    }

    /**
     * 执行单个阶段（执行异常时返回 null，由调用方统一按失败策略处理）。
     */
    private String runStage(OrchestrationContext ctx, String prompt, Agent agent, String stageId) {
        try {
            return ctx.getExecutionUnit().runAgent(prompt, agent, ctx.getCallback(), ctx.getStreamCallback());
        } catch (Exception e) {
            log.warn("流水线阶段 {} 执行失败: {}", stageId, e.getMessage());
            return null;
        }
    }

    private String workdir(OrchestrationDefinition definition) {
        Object raw = definition.getConfig().get("workdir");
        return raw == null || String.valueOf(raw).trim().isEmpty()
                ? DEFAULT_WORKDIR : String.valueOf(raw);
    }

    private String renderTemplate(String template, String input) {
        String result = template == null ? "" : template;
        result = result.replace("{input}", input == null ? "" : input);
        result = result.replace("{artifacts}", DEFAULT_WORKDIR);
        return result;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }
}
