package com.mwb.ai.claw.shell;

import java.text.DecimalFormat;
import java.util.List;

import javax.annotation.Resource;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.collaboration.spi.ExecutionUnit;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.observability.TraceStore;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.eval.app.DatasetInfo;
import com.mwb.ai.claw.eval.app.EvalRunResult;
import com.mwb.ai.claw.eval.app.EvalService;
import com.mwb.ai.claw.eval.dataset.DatasetLoader;
import com.mwb.ai.claw.eval.judge.LlmJudgeEvaluator;
import com.mwb.ai.claw.eval.model.CaseResult;
import com.mwb.ai.claw.eval.model.EvalConfig;
import com.mwb.ai.claw.eval.model.EvalDiff;
import com.mwb.ai.claw.eval.model.EvalReport;
import com.mwb.ai.claw.eval.model.EvalSummary;
import com.mwb.ai.claw.eval.model.TraceDiffStatus;
import com.mwb.ai.claw.eval.report.ReportWriter;
import com.mwb.ai.claw.eval.runner.EvalRunner;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;

/**
 * {@code eval} 命令门面（adapter 层）：用运行时 Bean 装配 {@link EvalService}，为
 * /eval run/report/diff/ls 提供纯文本输出。
 * <p>
 * 只依赖 domain/infra 已暴露的 Bean（ExecutionUnit / AgentGateway / LlmGateway / MetricsRecorder），
 * 不改动核心执行链路。裁判复用目标 Agent 的模型配置，可按需覆盖 judgeModel。
 */
@Component
@Profile("shell")
public class EvalCommandService {

    private static final AgentScope SCOPE = AgentScope.of("default", "default");
    private static final String DEFAULT_AGENT = "default";
    private static final DecimalFormat pct = new DecimalFormat("0.00%");
    private static final DecimalFormat two = new DecimalFormat("0.00");

    @Resource
    private ExecutionUnit executionUnit;

    @Resource
    private AgentGateway agentGateway;

    @Resource
    private LlmGateway llmGateway;

    @Resource
    private MetricsRecorder metricsRecorder;

    /** golden trace 存储（agent.observability.trace.store 装配；未装配时跳过 trace 对比） */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<TraceStore> traceStoreProvider;

    /** /eval 帮助文本 */
    public String help() {
        return "/eval run <datasetPath> [agentId] [judge] [judgeModel] [recordTraces=true]\n"
                + "/eval report <reportPath>\n"
                + "/eval diff <baselineReport> <currentReport>\n"
                + "/eval ls [datasetDir]";
    }

    /** 运行评测：加载数据集 → 执行 Agent → 判定 → 落盘 JSON/Markdown 报告。 */
    public String run(String datasetPath, String agentId, String judge, String judgeModel) {
        return run(datasetPath, agentId, judge, judgeModel, null);
    }

    /**
     * 运行评测。{@code recordTraces} 为 true 时落 golden baseline trace（本次即基线）；
     * false 时对比既有基线（存在则产出 traceDiffStatus）。
     */
    public String run(String datasetPath, String agentId, String judge, String judgeModel, Boolean recordTraces) {
        try {
            if (datasetPath == null || datasetPath.trim().isEmpty()) {
                return "用法: /eval run <datasetPath> [agentId] [judge=rule|llm|both] [judgeModel] [recordTraces=true]";
            }
            String strategy = judge == null || judge.trim().isEmpty() ? "both" : judge.trim();
            String resolvedAgentId = agentId == null || agentId.trim().isEmpty() ? DEFAULT_AGENT : agentId.trim();
            Agent agent = agentGateway.getAgent(resolvedAgentId);

            EvalRunner runner = buildRunner(strategy, agent, judgeModel);
            EvalService service = new EvalService(runner, new DatasetLoader(), new ReportWriter());

            EvalConfig config = new EvalConfig();
            config.setDatasetPath(datasetPath.trim());
            config.setAgentId(resolvedAgentId);
            config.setJudge(strategy);
            config.setRecordTraces(recordTraces != null && recordTraces);

            EvalRunResult result = service.run(config);
            return formatRunResult(result);
        } catch (Exception e) {
            return "评测失败: " + e.getMessage();
        }
    }

    /** 查看既有 JSON 报告摘要。 */
    public String report(String reportPath) {
        try {
            if (reportPath == null || reportPath.trim().isEmpty()) {
                return "用法: /eval report <reportPath>";
            }
            EvalReport report = readService().loadReport(reportPath.trim());
            return formatReport(report);
        } catch (Exception e) {
            return "读取报告失败: " + e.getMessage();
        }
    }

    /** 对比两份报告，输出回归差异。 */
    public String diff(String baselineReport, String currentReport) {
        try {
            if (baselineReport == null || currentReport == null
                    || baselineReport.trim().isEmpty() || currentReport.trim().isEmpty()) {
                return "用法: /eval diff <baselineReport> <currentReport>";
            }
            EvalDiff diff = readService().diff(baselineReport.trim(), currentReport.trim());
            return formatDiff(diff);
        } catch (Exception e) {
            return "对比失败: " + e.getMessage();
        }
    }

    /** 列出数据集目录中的可用数据集。 */
    public String ls(String datasetDir) {
        StringBuilder sb = new StringBuilder();
        List<DatasetInfo> infos = readService().listDatasets(datasetDir);
        if (infos.isEmpty()) {
            return "（目录为空或不存在，可通过参数指定数据集目录）";
        }
        sb.append("taskId | name | cases | file\n");
        for (DatasetInfo info : infos) {
            if (info.isLoaded()) {
                sb.append(info.getTaskId()).append(" | ").append(nvl(info.getName()))
                        .append(" | ").append(info.getCaseCount())
                        .append(" | ").append(info.getFile()).append('\n');
            } else {
                sb.append("(读取失败) | - | - | ").append(info.getFile())
                        .append(" (").append(nvl(info.getError())).append(")\n");
            }
        }
        return sb.toString().trim();
    }

    /** 只用于查看/对比/列举的只读服务（run/reportWriter 不需要，置空）。 */
    private EvalService readService() {
        return new EvalService(null, new DatasetLoader(), null);
    }

    private EvalRunner buildRunner(String strategy, Agent agent, String judgeModel) {
        boolean needLlm = "llm".equals(strategy) || "both".equals(strategy);
        LlmJudgeEvaluator llmJudge = null;
        if (needLlm) {
            ModelConfig judgeConfig = resolveJudgeModel(agent, judgeModel);
            if (judgeConfig == null) {
                throw new IllegalArgumentException("LLM 裁判需要模型配置：请在 Agent 上绑定模型，或用 judgeModel 指定裁判模型");
            }
            llmJudge = new LlmJudgeEvaluator(llmGateway, judgeConfig);
        }
        return new EvalRunner(executionUnit, agentGateway, metricsRecorder, SCOPE, llmJudge,
                traceStoreProvider.getIfAvailable());
    }

    private ModelConfig resolveJudgeModel(Agent agent, String judgeModel) {
        ModelConfig base = agent == null ? null : agent.getModelConfig();
        if (judgeModel == null || judgeModel.trim().isEmpty()) {
            return base;
        }
        ModelConfig mc = base == null ? new ModelConfig() : copyModelConfig(base);
        mc.setModel(judgeModel.trim());
        return mc;
    }

    private ModelConfig copyModelConfig(ModelConfig src) {
        ModelConfig mc = new ModelConfig();
        mc.setModel(src.getModel());
        mc.setProvider(src.getProvider());
        mc.setBaseUrl(src.getBaseUrl());
        mc.setApiKey(src.getApiKey());
        mc.setTemperature(src.getTemperature());
        mc.setMaxTokens(src.getMaxTokens());
        mc.setThinking(src.getThinking());
        return mc;
    }

    private String formatRunResult(EvalRunResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(formatReport(result.getReport()));
        sb.append('\n').append("报告: ").append(result.getMdPath());
        sb.append('\n').append("JSON: ").append(result.getJsonPath());
        return sb.toString();
    }

    private String formatReport(EvalReport report) {
        EvalSummary s = report.getSummary() == null ? new EvalSummary() : report.getSummary();
        StringBuilder sb = new StringBuilder();
        sb.append("评测报告: ").append(nvl(report.getTaskName())).append(" (").append(nvl(report.getTaskId()))
                .append(")\n");
        sb.append("通过率: ").append(pct.format(s.getPassRate()))
                .append(" · ").append(s.getPassed()).append("/").append(s.getTotal())
                .append(" · 平均耗时 ").append(two.format(s.getAvgDurationMs())).append("ms")
                .append(" · avgTokens ").append(two.format(s.getAvgTokens()))
                .append(" · cost ").append(two.format(s.getCost())).append(" 元");
        if (report.getMeta() != null && report.getMeta().getModel() != null) {
            sb.append("\n模型: ").append(nvl(report.getMeta().getModel()))
                    .append(" · 裁判: ").append(nvl(report.getMeta().getJudgeModel()))
                    .append(" · 策略: ").append(nvl(report.getMeta().getJudge()))
                    .append(" · 数据集 v").append(nvl(report.getMeta().getDatasetVersion()));
        }
        if (report.getCases() != null) {
            for (CaseResult cr : report.getCases()) {
                if (cr.getTraceDiffStatus() == TraceDiffStatus.REGRESSED) {
                    sb.append("\n⚠ trace 回归: ").append(nvl(cr.getCaseId()))
                            .append(" · ").append(nvl(cr.getTraceDiffDetails()));
                }
            }
        }
        return sb.toString();
    }

    private String formatDiff(EvalDiff diff) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务 ").append(nvl(diff.getTaskId()))
                .append(" · 通过率 ").append(pct.format(diff.getBaselinePassRate()))
                .append(" → ").append(pct.format(diff.getCurrentPassRate()))
                .append(" (delta ").append(two.format(diff.getPassRateDelta() * 100)).append("%)")
                .append(diff.isRegression() ? " ⚠ 判定回归" : " ✓ 无回归")
                .append('\n');
        sb.append("回归 ").append(diff.getRegressedCount()).append(" · 修复 ")
                .append(diff.getImprovedCount()).append('\n');
        if (diff.getItems() != null) {
            for (com.mwb.ai.claw.eval.model.EvalDiffItem item : diff.getItems()) {
                sb.append("  ").append(item.getStatus())
                        .append(" · ").append(nvl(item.getCaseId()))
                        .append(" · ").append(nvl(item.getName()))
                        .append(" · base=").append(item.isBaselinePassed())
                        .append(" cur=").append(item.isCurrentPassed())
                        .append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }
}