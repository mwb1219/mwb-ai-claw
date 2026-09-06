package com.mwb.ai.claw.eval.maven;

import java.io.IOException;
import java.text.DecimalFormat;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.mwb.ai.claw.eval.app.EvalService;
import com.mwb.ai.claw.eval.model.EvalMeta;
import com.mwb.ai.claw.eval.model.EvalReport;
import com.mwb.ai.claw.eval.model.EvalSummary;

/**
 * {@code eval:report} —— 打印既有评测报告摘要（CI 可读性）。
 * <p>
 * 参数：{@code eval.report}（必填）报告 JSON 路径。
 */
@Mojo(name = "report", requiresProject = false, threadSafe = true)
public class ReportMojo extends AbstractMojo {

    private static final DecimalFormat PCT = new DecimalFormat("0.00%");
    private static final DecimalFormat TWO = new DecimalFormat("0.00");

    /** 报告（JSON）路径。 */
    @Parameter(property = "eval.report", required = true)
    private String report;

    @Override
    public void execute() throws MojoExecutionException {
        EvalReport r;
        try {
            r = new EvalService(null, null, null).loadReport(report);
        } catch (IOException e) {
            throw new MojoExecutionException("无法读取评测报告: " + report, e);
        }
        EvalSummary s = r.getSummary() == null ? new EvalSummary() : r.getSummary();
        EvalMeta m = r.getMeta();

        getLog().info("评测报告: " + nvl(r.getTaskName()) + " (" + nvl(r.getTaskId()) + ")");
        getLog().info("  通过率 " + PCT.format(s.getPassRate()) + "（" + s.getPassed() + "/" + s.getTotal() + "）"
                + " · 平均耗时 " + TWO.format(s.getAvgDurationMs()) + "ms"
                + " · avgTokens " + TWO.format(s.getAvgTokens())
                + " · 累计 token " + s.getTotalTokens()
                + " · cost " + TWO.format(s.getCost()) + " 元");
        if (m != null) {
            getLog().info("  模型 " + nvl(m.getModel()) + " · 裁判 " + nvl(m.getJudgeModel())
                    + " · 策略 " + nvl(m.getJudge()) + " · 数据集 v" + nvl(m.getDatasetVersion()));
        }
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }
}