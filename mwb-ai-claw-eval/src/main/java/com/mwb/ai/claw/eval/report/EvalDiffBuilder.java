package com.mwb.ai.claw.eval.report;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mwb.ai.claw.eval.model.CaseResult;
import com.mwb.ai.claw.eval.model.DiffStatus;
import com.mwb.ai.claw.eval.model.EvalDiff;
import com.mwb.ai.claw.eval.model.EvalDiffItem;
import com.mwb.ai.claw.eval.model.EvalReport;

/**
 * 回归对比构建器：按 caseId 对齐两份 {@link EvalReport}（baseline vs current），
 * 逐用例标记 {@code improved / regressed / unchanged / new / missing}，并给出通过率 delta 与整体回归判定。
 * <p>
 * 判定口径：regressed = baseline 通过且 current 失败；improved = baseline 失败且 current 通过；
 * 整体 regression = 存在 REGRESSED 用例，或 current 通过率低于 baseline。
 */
public final class EvalDiffBuilder {

    private EvalDiffBuilder() {
    }

    /**
     * @param baseline baseline 报告（可为 null，视为全空 baseline）
     * @param current  current 报告（可为 null，视为空 current，全部 MISSING）
     * @return 回归对比结果；baseline/current 均为 null 时返回空 diff
     */
    public static EvalDiff build(EvalReport baseline, EvalReport current) {
        EvalDiff diff = new EvalDiff();
        diff.setTaskId(current == null ? (baseline == null ? null : baseline.getTaskId()) : current.getTaskId());

        double baselineRate = passRate(baseline);
        double currentRate = passRate(current);
        diff.setBaselinePassRate(baselineRate);
        diff.setCurrentPassRate(currentRate);
        diff.setPassRateDelta(currentRate - baselineRate);

        Map<String, CaseResult> base = index(baseline);
        Map<String, CaseResult> cur = index(current);
        Set<String> ids = new LinkedHashSet<>(cur.keySet()); // current 顺序在前
        ids.addAll(base.keySet());

        int regressed = 0;
        int improved = 0;
        for (String id : ids) {
            CaseResult br = base.get(id);
            CaseResult cr = cur.get(id);
            EvalDiffItem item = new EvalDiffItem();
            item.setCaseId(id);
            if (br != null && cr != null) {
                item.setName(cr.getName() != null ? cr.getName() : br.getName());
                item.setBaselinePassed(br.isPassed());
                item.setCurrentPassed(cr.isPassed());
                item.setBaselineScore(br.getScore());
                item.setCurrentScore(cr.getScore());
                if (!br.isPassed() && cr.isPassed()) {
                    item.setStatus(DiffStatus.IMPROVED);
                    improved++;
                } else if (br.isPassed() && !cr.isPassed()) {
                    item.setStatus(DiffStatus.REGRESSED);
                    regressed++;
                } else {
                    item.setStatus(DiffStatus.UNCHANGED);
                }
            } else if (cr != null) {
                item.setName(cr.getName());
                item.setCurrentPassed(cr.isPassed());
                item.setCurrentScore(cr.getScore());
                item.setStatus(DiffStatus.NEW);
            } else {
                item.setName(br.getName());
                item.setBaselinePassed(br.isPassed());
                item.setBaselineScore(br.getScore());
                item.setStatus(DiffStatus.MISSING);
            }
            diff.getItems().add(item);
        }
        diff.setRegressedCount(regressed);
        diff.setImprovedCount(improved);
        diff.setRegression(regressed > 0 || currentRate < baselineRate);
        return diff;
    }

    private static double passRate(EvalReport r) {
        return r != null && r.getSummary() != null ? r.getSummary().getPassRate() : 0D;
    }

    private static Map<String, CaseResult> index(EvalReport r) {
        Map<String, CaseResult> m = new LinkedHashMap<>();
        if (r == null) {
            return m;
        }
        List<CaseResult> cases = r.getCases();
        if (cases == null) {
            return m;
        }
        for (CaseResult c : cases) {
            if (c != null && c.getCaseId() != null) {
                m.put(c.getCaseId(), c);
            }
        }
        return m;
    }
}