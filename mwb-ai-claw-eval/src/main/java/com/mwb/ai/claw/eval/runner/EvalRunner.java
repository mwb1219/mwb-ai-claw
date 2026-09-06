package com.mwb.ai.claw.eval.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mwb.ai.claw.domain.collaboration.spi.ExecutionUnit;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ReActResult;
import com.mwb.ai.claw.domain.observability.TraceRun;
import com.mwb.ai.claw.domain.observability.TraceStep;
import com.mwb.ai.claw.domain.observability.TraceStore;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.eval.judge.JudgeVerdict;
import com.mwb.ai.claw.eval.judge.LlmJudgeEvaluator;
import com.mwb.ai.claw.eval.judge.RuleEvaluator;
import com.mwb.ai.claw.eval.model.CaseResult;
import com.mwb.ai.claw.eval.model.EvalCase;
import com.mwb.ai.claw.eval.model.EvalConfig;
import com.mwb.ai.claw.eval.model.EvalDataset;
import com.mwb.ai.claw.eval.model.EvalMeta;
import com.mwb.ai.claw.eval.model.EvalReport;
import com.mwb.ai.claw.eval.model.JudgeType;
import com.mwb.ai.claw.eval.model.TraceDiffStatus;
import com.mwb.ai.claw.eval.report.EvalSummarizer;
import com.mwb.ai.claw.eval.report.TraceDiff;
import com.mwb.ai.claw.eval.report.TraceDiffBuilder;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;

/**
 * 评测执行器：逐个 case 用 {@link ExecutionUnit.runAgent} 程序化执行 Agent，
 * 用 {@link MetricsRecorder.snapshot()} 前后 diff 得到该 case 的 token 与耗时，再按判定策略打分。
 * <p>
 * 判定策略（见 {@link EvalConfig#getJudge()}）：
 * {@code rule} 仅确定性规则；{@code llm} 仅 LLM 裁判；{@code both}（默认）规则命中优先、未命中回退 LLM。
 */
public class EvalRunner {

    private final ExecutionUnit executionUnit;
    private final AgentGateway agentGateway;
    private final MetricsRecorder metrics;
    private final AgentScope scope;
    private final RuleEvaluator ruleEvaluator = new RuleEvaluator();
    private final LlmJudgeEvaluator llmJudge;
    private final TraceStore traceStore;

    /**
     * @param llmJudge LLM 裁判；当判定策略不涉及 llm（rule only）或未装配时可传 null
     */
    public EvalRunner(ExecutionUnit executionUnit, AgentGateway agentGateway, MetricsRecorder metrics,
                      AgentScope scope, LlmJudgeEvaluator llmJudge) {
        this(executionUnit, agentGateway, metrics, scope, llmJudge, null);
    }

    /**
     * @param llmJudge   LLM 裁判；当判定策略不涉及 llm（rule only）或未装配时可传 null
     * @param traceStore golden trace 存储；未装配（null）时跳过 trace 采集与对比，traceDiffStatus 恒为 NO_BASELINE
     */
    public EvalRunner(ExecutionUnit executionUnit, AgentGateway agentGateway, MetricsRecorder metrics,
                      AgentScope scope, LlmJudgeEvaluator llmJudge, TraceStore traceStore) {
        this.executionUnit = executionUnit;
        this.agentGateway = agentGateway;
        this.metrics = metrics;
        this.scope = scope;
        this.llmJudge = llmJudge;
        this.traceStore = traceStore;
    }

    /** 运行整组数据集，返回评测报告。 */
    public EvalReport run(EvalConfig config, EvalDataset dataset) {
        Agent agent = agentGateway.getAgent(config.getAgentId());
        EvalReport report = new EvalReport();
        report.setTaskId(dataset.getTask().getId());
        report.setTaskName(dataset.getTask().getName());
        report.setRunAt(System.currentTimeMillis());
        report.setMeta(buildMeta(config, dataset, agent));

        List<CaseResult> results = new ArrayList<>();
        String taskId = dataset.getTask() == null ? null : dataset.getTask().getId();
        for (EvalCase c : dataset.getCases()) {
            results.add(runCase(config, agent, c, taskId));
        }
        report.setCases(results);
        report.setSummary(EvalSummarizer.summarize(report, config));
        return report;
    }

    private CaseResult runCase(EvalConfig config, Agent agent, EvalCase c, String taskId) {
        CaseResult cr = new CaseResult();
        cr.setCaseId(c.getId());
        cr.setName(c.getName());

        long t0 = System.currentTimeMillis();
        long tokenBefore = metricTokens();
        String reply = "";
        ReActResult react = null;
        try {
            react = executionUnit.runAgentResult(c.getPrompt(), agent, null, null);
            reply = react == null ? "" : react.getReply();
            cr.setReply(reply);
        } catch (Exception e) {
            cr.setError(e.getMessage());
        } finally {
            cr.setDurationMs(System.currentTimeMillis() - t0);
            cr.setTokens(metricTokens() - tokenBefore);
        }

        if (cr.getError() != null) {
            cr.setPassed(false);
            cr.setJudge(resolveFallbackJudge(config));
            return cr;
        }

        JudgeVerdict verdict = judge(config, c, reply);
        if (verdict != null) {
            cr.setJudge(verdict.getJudge());
            cr.setPassed(verdict.isPassed());
            cr.setScore(verdict.getScore());
            cr.setVerdict(verdict.getVerdict());
        } else {
            // 判定不可用：回退简单相等比较
            cr.setJudge(resolveFallbackJudge(config));
            cr.setPassed(c.getExpected() != null && c.getExpected().trim().equals(reply.trim()));
        }
        collectTrace(config, cr, c, react, agent, t0, taskId);
        return cr;
    }

    /** 判定调度：按策略选 rule → llm → 兜底。 */
    private JudgeVerdict judge(EvalConfig config, EvalCase c, String reply) {
        String strategy = config.getJudge() == null ? "both" : config.getJudge();
        boolean allowRule = "rule".equals(strategy) || "both".equals(strategy);
        boolean allowLlm = "llm".equals(strategy) || "both".equals(strategy);

        if (allowRule) {
            JudgeVerdict r = ruleEvaluator.judge(c, reply);
            if (r != null) {
                return r;
            }
        }
        if (allowLlm && llmJudge != null) {
            JudgeVerdict l = llmJudge.judge(c, reply);
            if (l != null) {
                return l;
            }
        }
        return null;
    }

    private JudgeType resolveFallbackJudge(EvalConfig config) {
        String strategy = config.getJudge() == null ? "both" : config.getJudge();
        return "llm".equals(strategy) || "both".equals(strategy) ? JudgeType.LLM : JudgeType.RULE;
    }

    // ==================== golden trace 采集与对比 ====================

    /**
     * 采集/对比 golden trace（TraceStore 未装配时静默跳过，traceDiffStatus 恒为 NO_BASELINE）。
     * {@code recordTraces=true} 时以确定性 traceId 覆写该 case 基线（本次即基线，不对比）；
     * 否则为当前产物落唯一 id，读取基线做步骤级归一化 diff。
     */
    private void collectTrace(EvalConfig config, CaseResult cr, EvalCase c, ReActResult react,
                              Agent agent, long startTime, String taskId) {
        if (traceStore == null) {
            cr.setTraceDiffStatus(TraceDiffStatus.NO_BASELINE);
            return;
        }
        if (config.isRecordTraces()) {
            TraceRun golden = buildTraceRun(config, c, react, agent, startTime, taskId);
            golden.setTraceId(traceBaselineId(taskId, c));
            traceStore.saveTrace(golden);
            cr.setTraceDiffStatus(TraceDiffStatus.NO_BASELINE); // 本次即基线，无对比
        } else {
            TraceRun current = buildTraceRun(config, c, react, agent, startTime, taskId);
            current.setTraceId(genTraceId());
            traceStore.saveTrace(current);
            TraceRun baseline = traceStore.findTrace(scope, traceBaselineId(taskId, c));
            if (baseline != null) {
                TraceDiff diff = TraceDiffBuilder.compare(baseline, current);
                cr.setTraceDiffStatus(diff.getStatus());
                cr.setTraceDiffDetails(diff.getDetails());
            } else {
                cr.setTraceDiffStatus(TraceDiffStatus.NO_BASELINE);
            }
        }
    }

    private TraceRun buildTraceRun(EvalConfig config, EvalCase c, ReActResult react,
                                   Agent agent, long startTime, String taskId) {
        TraceRun run = new TraceRun();
        run.setTenantId(scope == null ? "" : scope.getTenantId());
        run.setUserId(scope == null ? "" : scope.getUserId());
        run.setAgentId(config.getAgentId());
        run.setOrchestration("eval");
        run.setModel(agent != null && agent.getModelConfig() != null ? agent.getModelConfig().getModel() : null);
        run.setStartTime(startTime);
        run.setDurationMs(System.currentTimeMillis() - startTime);
        run.setSuccess(react == null || react.isSuccess());
        run.setErrorCode(react != null && !react.isSuccess() ? "EVAL_RUN_FAILED" : null);
        run.setSteps(buildTraceSteps(react));
        return run;
    }

    private List<TraceStep> buildTraceSteps(ReActResult react) {
        List<TraceStep> steps = new ArrayList<>();
        if (react == null || react.getTraceSteps() == null) {
            return steps;
        }
        int idx = 1;
        for (String s : react.getTraceSteps()) {
            if (s == null) {
                continue;
            }
            TraceStep step = new TraceStep();
            step.setIndex(idx++);
            step.setContent(s);
            step.setType(classifyStep(s));
            steps.add(step);
        }
        return steps;
    }

    private String classifyStep(String content) {
        if (content == null) {
            return "step";
        }
        String u = content.toUpperCase(Locale.ROOT);
        if (u.startsWith("[THOUGHT]")) {
            return "thought";
        }
        if (u.startsWith("[ACTION]")) {
            return "action";
        }
        if (u.startsWith("[OBSERVATION]")) {
            return "observation";
        }
        if (u.startsWith("[INFO]")) {
            return "info";
        }
        return "step";
    }

    /** 确定性 baseline traceId：含租户，跨租户隔离；同 task+case 基线固定、可被 recordTraces 模式覆写。 */
    private String traceBaselineId(String taskId, EvalCase c) {
        String tenant = nullTo(scope == null ? null : scope.getTenantId(), "default");
        return "eval-" + tenant + "-" + nullTo(taskId, "unknown") + "-" + nullTo(c.getId(), "case");
    }

    private String genTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String nullTo(String s, String def) {
        return s == null || s.trim().isEmpty() ? def : s.trim();
    }

    private long metricTokens() {
        long total = 0;
        for (Map<String, Object> m : metrics.snapshot()) {
            if ("claw.llm.token".equals(m.get("name")) && m.get("count") instanceof Number) {
                total += ((Number) m.get("count")).longValue();
            }
        }
        return total;
    }

    private EvalMeta buildMeta(EvalConfig config, EvalDataset dataset, Agent agent) {
        EvalMeta meta = new EvalMeta();
        meta.setAgentId(config.getAgentId());
        meta.setJudge(config.getJudge());
        meta.setJudgeModel(config.getJudgeModel());
        meta.setDatasetVersion(dataset.getTask().getVersion());
        if (agent != null && agent.getModelConfig() != null) {
            meta.setModel(agent.getModelConfig().getModel());
        } else {
            meta.setModel(config.getJudgeModel());
        }
        // 环境指纹：由 model+judgeModel+策略哈希，便于识别报告归属
        meta.setEnvHash(Integer.toHexString((meta.getModel() + "|" + meta.getJudgeModel() + "|" + meta.getJudge()).hashCode()));
        return meta;
    }
}