package com.mwb.ai.claw.eval.maven;

import java.io.IOException;
import java.text.DecimalFormat;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.mwb.ai.claw.eval.app.EvalService;
import com.mwb.ai.claw.eval.model.EvalDiff;
import com.mwb.ai.claw.eval.model.EvalDiffItem;

/**
 * {@code eval:diff} —— 评测回归门。
 * <p>
 * 对比 baseline / current 两份 JSON 报告（由 shell「/eval run」或外部评测产出），
 * 依据通过率 delta 与逐用例回归做判定：回归且 {@code failOnRegression=true} 时抛
 * {@link MojoFailureException} 使构建失败，适于接入 CI 作为质量门。
 * <p>
 * 参数：<ul>
 *     <li>{@code eval.baseline}（必填）baseline 报告路径</li>
 *     <li>{@code eval.current}（必填）current 报告路径</li>
 *     <li>{@code eval.failOnRegression}（默认 {@code true}）回归时是否使构建失败</li>
 * </ul>
 */
@Mojo(name = "diff", requiresProject = false, threadSafe = true)
public class DiffMojo extends AbstractMojo {

    private static final DecimalFormat PCT = new DecimalFormat("0.00%");
    private static final DecimalFormat TWO = new DecimalFormat("0.00");

    /** baseline 报告（JSON）路径。 */
    @Parameter(property = "eval.baseline", required = true)
    private String baseline;

    /** current 报告（JSON）路径。 */
    @Parameter(property = "eval.current", required = true)
    private String current;

    /** 回归时是否使构建失败（默认 true；false 则仅告警）。 */
    @Parameter(property = "eval.failOnRegression", defaultValue = "true")
    private boolean failOnRegression = true;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        EvalService service = new EvalService(null, null, null);
        EvalDiff diff;
        try {
            diff = service.diff(baseline, current);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "无法读取评测报告: baseline=" + baseline + ", current=" + current, e);
        }

        getLog().info("评测回归对比 task=" + nvl(diff.getTaskId())
                + " 通过率 " + PCT.format(diff.getBaselinePassRate())
                + " -> " + PCT.format(diff.getCurrentPassRate())
                + " (delta " + TWO.format(diff.getPassRateDelta() * 100) + "pp)"
                + "，回归=" + diff.getRegressedCount() + "，修复=" + diff.getImprovedCount());

        if (diff.getItems() != null) {
            for (EvalDiffItem item : diff.getItems()) {
                if (item.getStatus() != null
                        && item.getStatus().name().equals("REGRESSED")) {
                    getLog().warn("回归用例: " + nvl(item.getCaseId()) + " · " + nvl(item.getName())
                            + "（baseline 通过 -> current 失败）");
                }
            }
        }

        if (diff.isRegression()) {
            if (failOnRegression) {
                throw new MojoFailureException("评测回归：通过率从 "
                        + PCT.format(diff.getBaselinePassRate()) + " 降至 "
                        + PCT.format(diff.getCurrentPassRate()) + "，回归 " + diff.getRegressedCount() + " 例");
            }
            getLog().warn("检测到评测回归，但 failOnRegression=false，仅告警不中断构建");
        } else {
            getLog().info("无评测回归");
        }
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }
}