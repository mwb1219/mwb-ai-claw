package com.mwb.ai.claw.eval.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mwb.ai.claw.eval.model.EvalReport;

import lombok.Data;

/**
 * 一次「评测运行」的产出：完整报告 + 已落盘的 JSON / Markdown 报告路径。
 * <p>
 * 供 CLI / Maven plugin 等触发方取用，落盘报告可被 {@code loadReport} 回读做 diff。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalRunResult {

    /** 完整评测报告（含 summary + 逐用例明细 + meta） */
    private EvalReport report;

    /** 已落盘的 JSON 报告路径（结构化，供 diff / CI 解析） */
    private String jsonPath;

    /** 已落盘的 Markdown 报告路径（人读） */
    private String mdPath;

    public EvalRunResult() {
    }

    public EvalRunResult(EvalReport report, String jsonPath, String mdPath) {
        this.report = report;
        this.jsonPath = jsonPath;
        this.mdPath = mdPath;
    }
}