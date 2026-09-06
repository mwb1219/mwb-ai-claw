package com.mwb.ai.claw.eval.app;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwb.ai.claw.eval.dataset.DatasetLoader;
import com.mwb.ai.claw.eval.model.EvalConfig;
import com.mwb.ai.claw.eval.model.EvalDataset;
import com.mwb.ai.claw.eval.model.EvalDiff;
import com.mwb.ai.claw.eval.model.EvalReport;
import com.mwb.ai.claw.eval.report.EvalDiffBuilder;
import com.mwb.ai.claw.eval.report.ReportWriter;
import com.mwb.ai.claw.eval.runner.EvalRunner;

/**
 * 评测编排门面：将「加载数据集 → 执行 → 判定 → 落盘报告」串成一次运行，并提供报告回读与回归对比能力。
 * <p>
 * 依赖注入 {@link EvalRunner}（已绑定执行单元 / 裁判），本类不感知具体 LLM / Agent 细节，
 * 供 CLI（{@code eval} 命令族）、Maven plugin、CI 复用同一入口。
 */
public class EvalService {

    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final String DEFAULT_OUTPUT = "./eval-report";

    private final EvalRunner runner;
    private final DatasetLoader datasetLoader;
    private final ReportWriter reportWriter;

    public EvalService(EvalRunner runner, DatasetLoader datasetLoader, ReportWriter reportWriter) {
        this.runner = runner;
        this.datasetLoader = datasetLoader;
        this.reportWriter = reportWriter;
    }

    /**
     * 运行一组数据集：加载 → 执行 → 落盘 JSON/Markdown 报告。
     *
     * @param config 评测配置（必须含 datasetPath；agentId/judge/output 为可覆盖项）
     * @return 报告 + 落盘路径
     */
    public EvalRunResult run(EvalConfig config) throws IOException {
        EvalConfig cfg = config == null ? new EvalConfig() : config;
        String ds = requireDs(cfg);
        EvalDataset dataset = datasetLoader.load(ds);
        EvalReport report = runner.run(cfg, dataset);
        String output = cfg.getOutput() == null || cfg.getOutput().trim().isEmpty()
                ? DEFAULT_OUTPUT : cfg.getOutput().trim();
        Path[] files = reportWriter.writeBoth(report, Paths.get(output));
        return new EvalRunResult(report, files[0].toString(), files[1].toString());
    }

    /** 从 JSON 报告文件回读 {@link EvalReport}（供 diff / report 查看）。 */
    public EvalReport loadReport(String reportPath) throws IOException {
        return JSON.readValue(Files.readAllBytes(Paths.get(reportPath)), EvalReport.class);
    }

    /** 对比两份 JSON 报告（baseline vs current），返回回归差异。 */
    public EvalDiff diff(String baselineReport, String currentReport) throws IOException {
        return EvalDiffBuilder.build(loadReport(baselineReport), loadReport(currentReport));
    }

    /** 扫描数据集目录，列出可用的数据集（含解析失败标注）。 */
    public List<DatasetInfo> listDatasets(String datasetDir) {
        Path dir = datasetDir == null || datasetDir.trim().isEmpty()
                ? Paths.get("./dataset") : Paths.get(datasetDir);
        List<DatasetInfo> infos = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return infos;
        }
        File[] files = dir.toFile().listFiles();
        if (files == null) {
            return infos;
        }
        java.util.Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            if (!f.isFile() || !isDatasetFile(f.getName())) {
                continue;
            }
            infos.add(describe(f));
        }
        return infos;
    }

    private DatasetInfo describe(File f) {
        try {
            EvalDataset ds = datasetLoader.load(f.getAbsolutePath());
            return new DatasetInfo(
                    ds.getTask().getId(),
                    ds.getTask().getName(),
                    f.getAbsolutePath(),
                    ds.getCases() == null ? 0 : ds.getCases().size());
        } catch (IllegalArgumentException e) {
            return DatasetInfo.failed(f.getAbsolutePath(), e.getMessage());
        }
    }

    private String requireDs(EvalConfig cfg) {
        String ds = cfg.getDatasetPath();
        if (ds == null || ds.trim().isEmpty()) {
            throw new IllegalArgumentException("评测需提供数据集文件路径（datasetPath）");
        }
        return ds.trim();
    }

    private boolean isDatasetFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".json") || lower.endsWith(".yaml") || lower.endsWith(".yml");
    }
}