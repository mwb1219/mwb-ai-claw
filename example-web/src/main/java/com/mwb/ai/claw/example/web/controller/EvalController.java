package com.mwb.ai.claw.example.web.controller;

import java.util.List;

import javax.annotation.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.collaboration.spi.ExecutionUnit;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.observability.TraceStore;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.eval.app.DatasetInfo;
import com.mwb.ai.claw.eval.app.EvalRunResult;
import com.mwb.ai.claw.eval.app.EvalService;
import com.mwb.ai.claw.eval.dataset.DatasetLoader;
import com.mwb.ai.claw.eval.judge.LlmJudgeEvaluator;
import com.mwb.ai.claw.eval.model.EvalConfig;
import com.mwb.ai.claw.eval.model.EvalDataset;
import com.mwb.ai.claw.eval.model.EvalDiff;
import com.mwb.ai.claw.eval.model.EvalReport;
import com.mwb.ai.claw.eval.report.ReportWriter;
import com.mwb.ai.claw.eval.runner.EvalRunner;
import com.mwb.ai.claw.example.web.dto.GenerateDatasetCommand;
import com.mwb.ai.claw.example.web.dto.GenerateDatasetResult;
import com.mwb.ai.claw.example.web.service.EvalDatasetGenerator;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;

/**
 * Agent 评测 REST 接口（example-web）：将 mwb-ai-claw 的 {@code /eval} 评测能力暴露为 web API，
 * 便于前端 / CI 通过 HTTP 触发 Agent 评测并读取报告。
 * <p>
 * 复用框架已暴露的核心 Bean（ExecutionUnit / AgentGateway / LlmGateway / MetricsRecorder / TraceStore）
 * 装配 {@link EvalService}，不改动核心执行链路。与 shell 版 {@code EvalCommandService} 保持同一套走跑逻辑。
 */
@RestController
@RequestMapping("/eval")
@Profile("web")
public class EvalController {

    private static final AgentScope SCOPE = AgentScope.of("default", "default");
    private static final String DEFAULT_AGENT = "default";

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
    private ObjectProvider<TraceStore> traceStoreProvider;

    /** LLM 驱动的数据集自动生成器 */
    @Resource
    private EvalDatasetGenerator evalDatasetGenerator;

    /** 数据集落盘目录（与 EvalDatasetGenerator 一致；/eval/ls 未指定 dir 时作为默认） */
    @Value("${example.eval.dataset-dir:./generated-datasets}")
    private String datasetDir;

    /** 报告落盘目录（/eval/reports 未指定 dir 时作为默认，与 EvalService 默认输出一致） */
    @Value("${example.eval.report-dir:./eval-report}")
    private String reportDir;

    /** 运行评测：加载数据集 → 执行 Agent → 判定 → 落盘 JSON/Markdown 报告。 */
    @PostMapping("/run")
    public SingleResponse<EvalRunResult> run(@RequestBody EvalConfig config) {
        try {
            if (config == null || config.getDatasetPath() == null || config.getDatasetPath().trim().isEmpty()) {
                return SingleResponse.buildFailure("EVAL_BAD_REQUEST", "评测需提供数据集文件路径（datasetPath）");
            }
            String strategy = config.getJudge() == null || config.getJudge().trim().isEmpty()
                    ? "both" : config.getJudge().trim();
            String resolvedAgentId = config.getAgentId() == null || config.getAgentId().trim().isEmpty()
                    ? DEFAULT_AGENT : config.getAgentId().trim();
            Agent agent = agentGateway.getAgent(resolvedAgentId);

            config.setDatasetPath(config.getDatasetPath().trim());
            config.setAgentId(resolvedAgentId);
            config.setJudge(strategy);

            EvalRunner runner = buildRunner(strategy, agent, config.getJudgeModel());
            EvalService service = new EvalService(runner, new DatasetLoader(), new ReportWriter());
            EvalRunResult result = service.run(config);
            return SingleResponse.of(result);
        } catch (Exception e) {
            return SingleResponse.buildFailure("EVAL_RUN_FAILED", "评测失败: " + e.getMessage());
        }
    }

    /** 用 LLM 按主题自动生成评测数据集并落盘为 JSON 文件，返回路径 + 数据集内容，可直接用于 /eval/run。 */
    @PostMapping("/dataset/generate")
    public SingleResponse<GenerateDatasetResult> generateDataset(@RequestBody GenerateDatasetCommand cmd) {
        try {
            if (cmd == null || cmd.getTopic() == null || cmd.getTopic().trim().isEmpty()) {
                return SingleResponse.buildFailure("EVAL_BAD_REQUEST", "生成数据集需提供主题（topic）");
            }
            GenerateDatasetResult result = evalDatasetGenerator.generate(cmd);
            return SingleResponse.of(result);
        } catch (Exception e) {
            return SingleResponse.buildFailure("EVAL_GENERATE_FAILED", "生成数据集失败: " + e.getMessage());
        }
    }

    /** 查看单个数据集的完整内容（task + cases，供前端展开查看用例详情）。 */
    @GetMapping("/dataset")
    public SingleResponse<EvalDataset> dataset(@RequestParam("path") String datasetPath) {
        try {
            if (datasetPath == null || datasetPath.trim().isEmpty()) {
                return SingleResponse.buildFailure("EVAL_BAD_REQUEST", "请提供数据集路径（path）");
            }
            EvalDataset dataset = readService().loadDataset(datasetPath.trim());
            return SingleResponse.of(dataset);
        } catch (Exception e) {
            return SingleResponse.buildFailure("EVAL_DATASET_FAILED", "读取数据集失败: " + e.getMessage());
        }
    }

    /** 查看既有 JSON 报告摘要。 */
    @GetMapping("/report")
    public SingleResponse<EvalReport> report(@RequestParam("path") String reportPath) {
        try {
            if (reportPath == null || reportPath.trim().isEmpty()) {
                return SingleResponse.buildFailure("EVAL_BAD_REQUEST", "请提供报告路径（path）");
            }
            EvalReport report = readService().loadReport(reportPath.trim());
            return SingleResponse.of(report);
        } catch (Exception e) {
            return SingleResponse.buildFailure("EVAL_REPORT_FAILED", "读取报告失败: " + e.getMessage());
        }
    }

    /** 对比两份报告，返回回归差异。 */
    @GetMapping("/diff")
    public SingleResponse<EvalDiff> diff(@RequestParam("baseline") String baselineReport,
                                         @RequestParam("current") String currentReport) {
        try {
            if (baselineReport == null || baselineReport.trim().isEmpty()
                    || currentReport == null || currentReport.trim().isEmpty()) {
                return SingleResponse.buildFailure("EVAL_BAD_REQUEST", "请提供 baseline 与 current 报告路径");
            }
            EvalDiff diff = readService().diff(baselineReport.trim(), currentReport.trim());
            return SingleResponse.of(diff);
        } catch (Exception e) {
            return SingleResponse.buildFailure("EVAL_DIFF_FAILED", "对比失败: " + e.getMessage());
        }
    }

    /** 列出数据集目录中的可用数据集。 */
    @GetMapping("/ls")
    public SingleResponse<List<DatasetInfo>> ls(@RequestParam(value = "dir", required = false) String datasetDir) {
        try {
            String resolvedDir = datasetDir == null || datasetDir.trim().isEmpty()
                    ? this.datasetDir : datasetDir.trim();
            List<DatasetInfo> infos = readService().listDatasets(resolvedDir);
            return SingleResponse.of(infos);
        } catch (Exception e) {
            return SingleResponse.buildFailure("EVAL_LS_FAILED", "列出数据集失败: " + e.getMessage());
        }
    }

    /** 列出报告目录中的可用 JSON 报告文件路径（供前端下拉选择 / 回归对比）。 */
    @GetMapping("/reports")
    public SingleResponse<List<String>> reports(@RequestParam(value = "dir", required = false) String reportDir) {
        try {
            String resolvedDir = reportDir == null || reportDir.trim().isEmpty()
                    ? this.reportDir : reportDir.trim();
            List<String> files = readService().listReportFiles(resolvedDir);
            return SingleResponse.of(files);
        } catch (Exception e) {
            return SingleResponse.buildFailure("EVAL_REPORTS_FAILED", "列出报告失败: " + e.getMessage());
        }
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
}
