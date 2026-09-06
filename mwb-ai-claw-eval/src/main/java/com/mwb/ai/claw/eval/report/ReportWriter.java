package com.mwb.ai.claw.eval.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mwb.ai.claw.eval.model.CaseResult;
import com.mwb.ai.claw.eval.model.EvalMeta;
import com.mwb.ai.claw.eval.model.EvalReport;
import com.mwb.ai.claw.eval.model.EvalSummary;

/**
 * 评测报告写出器：将 {@link EvalReport} 落盘为 JSON（结构化 / 供 diff、CI 解析）与 Markdown（人读）。
 * 文件名规则：{taskId}-{epochMillis}（输出目录不存在自动创建）。
 */
public class ReportWriter {

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** 同时写出 JSON 与 Markdown，返回两份文件路径 [{json, md}]。 */
    public Path[] writeBoth(EvalReport report, Path outputDir) throws IOException {
        return new Path[]{writeJson(report, outputDir), writeMarkdown(report, outputDir)};
    }

    /** 写出 JSON 报告，返回文件路径。 */
    public Path writeJson(EvalReport report, Path outputDir) throws IOException {
        Path file = outputDir.resolve(fileName(report, ".json"));
        write(file, mapper.writeValueAsString(report));
        return file;
    }

    /** 写出 Markdown 报告，返回文件路径。 */
    public Path writeMarkdown(EvalReport report, Path outputDir) throws IOException {
        Path file = outputDir.resolve(fileName(report, ".md"));
        write(file, toMarkdown(report));
        return file;
    }

    private String fileName(EvalReport report, String ext) {
        return report.getTaskId() + "-" + report.getRunAt() + ext;
    }

    private void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private String toMarkdown(EvalReport report) {
        DecimalFormat pct = new DecimalFormat("0.00%");
        DecimalFormat two = new DecimalFormat("0.00");
        EvalSummary s = report.getSummary();
        EvalMeta m = report.getMeta();

        StringBuilder sb = new StringBuilder();
        sb.append("# 评测报告: ").append(report.getTaskName()).append(" (").append(report.getTaskId()).append(")\n\n");
        sb.append("- 时间: `").append(report.getRunAt()).append("`\n");
        if (m != null) {
            sb.append("- Agent: `").append(nvl(m.getAgentId())).append("` · 模型: `").append(nvl(m.getModel()))
                    .append("` · 裁判模型: `").append(nvl(m.getJudgeModel())).append("` · 策略: `").append(nvl(m.getJudge()))
                    .append("` · 数据集 v").append(nvl(m.getDatasetVersion())).append("\n");
        }
        sb.append("\n## 摘要\n\n");
        sb.append("| 通过率 | 通过/总数 | 平均耗时(ms) | 平均 token | 累计 token | 成本(元) |\n");
        sb.append("| --- | --- | --- | --- | --- | --- |\n");
        sb.append("| ").append(pct.format(s.getPassRate()))
                .append(" | ").append(s.getPassed()).append("/").append(s.getTotal())
                .append(" | ").append(two.format(s.getAvgDurationMs()))
                .append(" | ").append(two.format(s.getAvgTokens()))
                .append(" | ").append(s.getTotalTokens())
                .append(" | ").append(two.format(s.getCost())).append(" |\n");

        sb.append("\n## 明细\n\n");
        sb.append("| 用例 | 通过 | 判定 | 得分 | 耗时(ms) | token | 说明 |\n");
        sb.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (CaseResult c : report.getCases()) {
            sb.append("| ").append(c.getCaseId().isEmpty() ? "?" : c.getCaseId())
                    .append(" | ").append(c.isPassed() ? "✅" : "❌")
                    .append(" | ").append(nvl(c.getJudge() == null ? "?" : c.getJudge().name()))
                    .append(" | ").append(c.getScore() == null ? "-" : two.format(c.getScore()))
                    .append(" | ").append(c.getDurationMs())
                    .append(" | ").append(c.getTokens())
                    .append(" | ").append(sanitize(nvl(c.getError() == null ? c.getVerdict() : c.getError())))
                    .append(" |\n");
        }
        return sb.toString();
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("|", "\\|").replace("\n", " ").trim();
    }
}