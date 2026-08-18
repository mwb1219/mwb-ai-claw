package com.mwb.ai.claw.infrastructure.collaboration.strategy;

import com.alibaba.cola.exception.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mwb.ai.claw.domain.collaboration.AgentOrchestrator;
import com.mwb.ai.claw.domain.collaboration.CollaborationResult;
import com.mwb.ai.claw.domain.collaboration.OrchestrationContext;
import com.mwb.ai.claw.domain.collaboration.OrchestrationDefinition;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.infrastructure.collaboration.ConversationDefinition;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 对话式编排（内置插件，type=conversational）：
 * 多个专家 Agent 围绕同一任务多轮讨论 —— 首轮并行产出各自观点，讨论轮串行互相回应，
 * 最后按收敛策略（consensus / moderator / best）产出最终结论。
 * <p>
 * 参与者通过临时会话执行（上下文隔离，不入库）；讨论历史按 visibleHistory 截断注入控制上下文占用。
 */
@Component
public class ConversationalOrchestrator implements AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ConversationalOrchestrator.class);

    /** 共识信号词（convergence=consensus 时用于统计支持数） */
    private static final List<String> CONSENSUS_MARKERS = Arrays.asList(
            "同意", "赞同", "支持", "一致", "认同", "赞成", "agree", "同意这个");

    /** 置信度标注格式：如「置信度: 0.8」（convergence=best 时解析） */
    private static final Pattern CONFIDENCE_PATTERN =
            Pattern.compile("置信度\\s*[:：]\\s*(0(?:\\.\\d+)?|1(?:\\.0+)?)");

    /** 首轮并行讨论线程池（daemon 线程，随 JVM 退出） */
    private static final ExecutorService DISCUSSION_POOL = Executors.newFixedThreadPool(
            4, r -> {
                Thread t = new Thread(r, "conversational-discussion");
                t.setDaemon(true);
                return t;
            });

    @Resource
    private AgentGateway agentGateway;

    @Override
    public String type() {
        return "conversational";
    }

    @Override
    public void validate(OrchestrationDefinition definition) {
        ConversationDefinition conv = conversation(definition);
        if (conv == null) {
            throw new IllegalArgumentException("对话式编排 '" + definition.getId() + "' 缺少 conversation 配置");
        }
        List<String> participants = conv.getParticipants();
        if (participants == null || participants.size() < 2) {
            throw new IllegalArgumentException("对话式编排 '" + definition.getId() + "' 的 participants 至少需要 2 个参与者");
        }
        Set<String> knownIds = agentGateway.listAgents().stream()
                .map(Agent::getAgentId).collect(Collectors.toSet());
        for (String participantId : participants) {
            if (participantId == null || participantId.trim().isEmpty()) {
                throw new IllegalArgumentException("对话式编排 '" + definition.getId() + "' 存在缺少 participant id 的参与者");
            }
            if (!knownIds.contains(participantId)) {
                throw new IllegalArgumentException("对话式编排 '" + definition.getId()
                        + "' 引用了不存在的参与者 Agent: " + participantId);
            }
        }
        String convergence = conv.convergenceOrDefault();
        if (!Arrays.asList("consensus", "moderator", "best").contains(convergence)) {
            throw new IllegalArgumentException("对话式编排 '" + definition.getId()
                    + "' 的收敛策略不合法: " + convergence);
        }
        if ("moderator".equals(convergence)) {
            String moderator = conv.getModerator();
            if (moderator == null || moderator.trim().isEmpty()) {
                throw new IllegalArgumentException("对话式编排 '" + definition.getId()
                        + "' 收敛策略为 moderator 时必须配置 moderator");
            }
            if (!knownIds.contains(moderator)) {
                throw new IllegalArgumentException("对话式编排 '" + definition.getId()
                        + "' 引用了不存在的 moderator Agent: " + moderator);
            }
        }
        if (conv.roundsOrDefault() < 1) {
            throw new IllegalArgumentException("对话式编排 '" + definition.getId() + "' 的 rounds 至少为 1");
        }
    }

    @Override
    public CollaborationResult orchestrate(OrchestrationContext ctx) {
        ConversationDefinition conv = conversationRequired(ctx.getDefinition());
        List<String> participants = conv.getParticipants();
        int rounds = conv.roundsOrDefault();
        int visibleHistory = conv.visibleHistoryOrDefault();
        String convergence = conv.convergenceOrDefault();

        if (ctx.getCallback() != null) {
            ctx.getCallback().onProgress("[Orchestration] 对话式讨论开始: " + participants.size()
                    + " 位参与者, " + rounds + " 轮");
        }

        // 讨论板：round -> participantId -> 发言
        Map<Integer, Map<String, String>> board = new LinkedHashMap<>();
        List<String> trace = new ArrayList<>();
        boolean consensusReached = false;

        // 1. 首轮（并行）：各参与者独立给出观点
        board.put(1, runFirstRound(ctx, conv, participants, trace));

        // 2. 讨论轮（串行）：r = 2..rounds，需看到其他参与者发言
        for (int r = 2; r <= rounds && !consensusReached; r++) {
            Map<String, String> round = new LinkedHashMap<>();
            for (String participantId : participants) {
                String history = buildVisibleHistory(board, r, participantId, visibleHistory);
                String prompt = "任务：" + ctx.getMessage()
                        + "\n\n以下是其他专家的观点：\n" + history
                        + "\n\n请回应：同意/质疑/补充，并给出你的结论（置信度 0-1）。"
                        + "\n不要调用任何工具，直接输出。";
                String reply = runParticipant(ctx, conv, participantId, prompt, "Round:" + r, trace);
                if (reply != null && !reply.trim().isEmpty()) {
                    round.put(participantId, reply);
                }
            }
            board.put(r, round);

            // 提前终止：convergence=consensus 且某观点支持数 >= minConsensus
            if ("consensus".equals(convergence) && consensusSupport(round) >= conv.minConsensusOrDefault()) {
                consensusReached = true;
                if (ctx.getCallback() != null) {
                    ctx.getCallback().onProgress("[Orchestration] 共识已达成，提前收敛");
                }
            }
        }

        // 3. 收敛
        ConvergeResult converge = converge(ctx, conv, board, trace);
        if (ctx.getCallback() != null) {
            ctx.getCallback().onProgress("[Orchestration] 收敛完成: " + convergence);
        }

        CollaborationResult cr = new CollaborationResult();
        cr.setReply(converge.reply);
        cr.setAgentId(converge.agentId);
        cr.setSessionId(ctx.getSessionId());
        cr.setOrchestrationId(ctx.getDefinition().getId());
        cr.setTraceSteps(trace);
        return cr;
    }

    // ---------------- 讨论轮 ----------------

    /** 首轮：并行收集各参与者观点（CompletableFuture + 线程池，并发数=参与者数） */
    private Map<String, String> runFirstRound(OrchestrationContext ctx, ConversationDefinition conv,
                                              List<String> participants, List<String> trace) {
        Map<String, String> replies = Collections.synchronizedMap(new LinkedHashMap<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String participantId : participants) {
            futures.add(CompletableFuture.runAsync(() -> {
                String prompt = "任务：" + ctx.getMessage()
                        + "\n请给出你的专业观点与理由（置信度 0-1）。\n不要调用任何工具，直接输出。";
                String reply = runParticipant(ctx, conv, participantId, prompt, "Round:1", trace);
                if (reply != null && !reply.trim().isEmpty()) {
                    replies.put(participantId, reply);
                }
            }, DISCUSSION_POOL));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 保持参与者声明顺序
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String participantId : participants) {
            if (replies.containsKey(participantId)) {
                ordered.put(participantId, replies.get(participantId));
            }
        }
        return ordered;
    }

    /** 执行单个参与者发言（临时会话，异常/空回复时返回 null，由调用方决定语义） */
    private String runParticipant(OrchestrationContext ctx, ConversationDefinition conv,
                                  String participantId, String prompt, String roundLabel, List<String> trace) {
        Agent agent = agentGateway.getAgent(participantId);
        if (conv.getThinking() != null) {
            agent.getModelConfig().setThinking(conv.getThinking());
        }
        String reply = runQuietly(ctx, prompt, agent, roundLabel);
        // 空回复容错：重试一次并要求直接输出
        if (reply == null || reply.trim().isEmpty()) {
            log.warn("对话式参与者 {} {} 回复为空，重试一次", participantId, roundLabel);
            reply = runQuietly(ctx, prompt + "\n\n（注意：你的上一条回复为空。请直接输出完整回答，不要调用任何工具，不要留空。）",
                    agent, roundLabel);
        }
        if (reply == null || reply.trim().isEmpty()) {
            return null;
        }
        String step = "[" + roundLabel + "] " + agent.getName() + ": " + truncate(reply);
        trace.add(step);
        if (ctx.getCallback() != null) {
            ctx.getCallback().onProgress(step);
        }
        return reply;
    }

    private String runQuietly(OrchestrationContext ctx, String prompt, Agent agent, String label) {
        try {
            return ctx.getExecutionUnit().runAgent(prompt, agent, ctx.getCallback());
        } catch (Exception e) {
            log.warn("对话式参与者 {} 执行失败: {}", label, e.getMessage());
            return null;
        }
    }

    /** 构建讨论轮可见历史：其他参与者最近 visibleHistory 轮的发言（不含自己） */
    private String buildVisibleHistory(Map<Integer, Map<String, String>> board, int currentRound,
                                       String participantId, int visibleHistory) {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(1, currentRound - visibleHistory);
        for (int r = start; r < currentRound; r++) {
            Map<String, String> round = board.get(r);
            if (round == null) {
                continue;
            }
            for (Map.Entry<String, String> entry : round.entrySet()) {
                if (entry.getKey().equals(participantId)) {
                    continue; // 不含自己的历史发言
                }
                sb.append("[第").append(r).append("轮 ").append(entry.getKey()).append("] ")
                        .append(entry.getValue()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** 统计一轮发言中的共识支持数（含共识信号词的发言数） */
    private int consensusSupport(Map<String, String> round) {
        int support = 0;
        for (String reply : round.values()) {
            if (reply == null || reply.trim().isEmpty()) {
                continue;
            }
            String lower = reply.toLowerCase();
            for (String marker : CONSENSUS_MARKERS) {
                if (lower.contains(marker)) {
                    support++;
                    break;
                }
            }
        }
        return support;
    }

    // ---------------- 收敛 ----------------

    /** 收敛：consensus（取支持最多的发言）| best（取置信度最高）| moderator（默认，仲裁汇总） */
    private ConvergeResult converge(OrchestrationContext ctx, ConversationDefinition conv,
                                    Map<Integer, Map<String, String>> board, List<String> trace) {
        String convergence = conv.convergenceOrDefault();
        switch (convergence) {
            case "consensus": {
                String best = null;
                String bestAgentId = null;
                int bestSupport = -1;
                for (Map.Entry<Integer, Map<String, String>> round : board.entrySet()) {
                    for (Map.Entry<String, String> entry : round.getValue().entrySet()) {
                        int support = consensusSupport(Collections.singletonMap(entry.getKey(), entry.getValue()));
                        if (support > bestSupport) {
                            bestSupport = support;
                            best = entry.getValue();
                            bestAgentId = entry.getKey();
                        }
                    }
                }
                if (best != null) {
                    trace.add("[Converge:consensus] " + bestAgentId + ": " + truncate(best));
                    return new ConvergeResult(best, bestAgentId);
                }
                // 无共识信号词，回退 moderator 汇总
                return convergeByModerator(ctx, conv, board, trace);
            }
            case "best": {
                String best = null;
                String bestAgentId = null;
                double bestConfidence = -1;
                for (Map.Entry<Integer, Map<String, String>> round : board.entrySet()) {
                    for (Map.Entry<String, String> entry : round.getValue().entrySet()) {
                        double confidence = parseConfidence(entry.getValue());
                        if (confidence > bestConfidence) {
                            bestConfidence = confidence;
                            best = entry.getValue();
                            bestAgentId = entry.getKey();
                        }
                    }
                }
                if (best != null) {
                    trace.add("[Converge:best] " + bestAgentId + "（置信度 " + bestConfidence + "）: " + truncate(best));
                    return new ConvergeResult(best, bestAgentId);
                }
                // 均未标注置信度，回退 moderator 汇总
                return convergeByModerator(ctx, conv, board, trace);
            }
            default: // moderator
                return convergeByModerator(ctx, conv, board, trace);
        }
    }

    /** moderator 收敛：仲裁 Agent 读取全部发言，汇总为最终结论 */
    private ConvergeResult convergeByModerator(OrchestrationContext ctx, ConversationDefinition conv,
                                               Map<Integer, Map<String, String>> board, List<String> trace) {
        StringBuilder transcript = new StringBuilder();
        for (Map.Entry<Integer, Map<String, String>> round : board.entrySet()) {
            transcript.append("【第").append(round.getKey()).append("轮】\n");
            for (Map.Entry<String, String> entry : round.getValue().entrySet()) {
                transcript.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
        String prompt = "任务：" + ctx.getMessage()
                + "\n\n以下是多位专家的讨论记录：\n" + transcript
                + "\n\n请作为决策主持，综合各方观点，给出明确且可执行的最终结论。"
                + "\n不要调用任何工具，直接输出。";

        Agent moderator = agentGateway.getAgent(conv.getModerator());
        if (conv.getThinking() != null) {
            moderator.getModelConfig().setThinking(conv.getThinking());
        }
        String reply = runQuietly(ctx, prompt, moderator, "Moderator");
        if (reply == null || reply.trim().isEmpty()) {
            log.warn("对话式 moderator {} 收敛回复为空，重试一次", conv.getModerator());
            reply = runQuietly(ctx, prompt + "\n\n（注意：请直接输出最终结论，不要留空。）", moderator, "Moderator");
        }
        if (reply == null || reply.trim().isEmpty()) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "对话式编排 moderator 收敛无产出: " + ctx.getDefinition().getId());
        }
        trace.add("[Converge:moderator] " + moderator.getName() + ": " + truncate(reply));
        if (ctx.getCallback() != null) {
            ctx.getCallback().onProgress("[Converge:moderator] " + moderator.getName() + ": " + truncate(reply));
        }
        return new ConvergeResult(reply, conv.getModerator());
    }

    /** 解析发言中的置信度标注（如「置信度: 0.8」），未标注返回 -1 */
    private double parseConfidence(String reply) {
        if (reply == null || reply.trim().isEmpty()) {
            return -1;
        }
        Matcher matcher = CONFIDENCE_PATTERN.matcher(reply);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    // ---------------- 解析 ----------------

    /** 解析对话式定义（缺少 conversation 配置时返回 null，供校验使用） */
    private ConversationDefinition conversation(OrchestrationDefinition definition) {
        Object raw = definition.getConfig().get("conversation");
        if (raw == null) {
            return null;
        }
        return JsonUtils.mapper().convertValue(raw, new TypeReference<ConversationDefinition>() {});
    }

    /** 解析对话式定义（缺少 conversation 配置时抛业务异常，供执行路径使用） */
    private ConversationDefinition conversationRequired(OrchestrationDefinition definition) {
        ConversationDefinition conv = conversation(definition);
        if (conv == null) {
            throw new BizException(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "对话式编排缺少 conversation 配置: " + definition.getId());
        }
        return conv;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }

    /** 收敛结果（最终回复 + 主导 Agent id） */
    private static class ConvergeResult {
        final String reply;
        final String agentId;

        ConvergeResult(String reply, String agentId) {
            this.reply = reply;
            this.agentId = agentId;
        }
    }
}
