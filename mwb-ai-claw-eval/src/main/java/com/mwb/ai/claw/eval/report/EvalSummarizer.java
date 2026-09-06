package com.mwb.ai.claw.eval.report;

import java.util.List;

import com.mwb.ai.claw.eval.model.CaseResult;
import com.mwb.ai.claw.eval.model.EvalConfig;
import com.mwb.ai.claw.eval.model.EvalReport;
import com.mwb.ai.claw.eval.model.EvalSummary;

/**
 * 评测摘要聚合：由各用例结果计算通过率 / 平均延迟 / 平均与累计 token / 估算成本。
 */
public final class EvalSummarizer {

    private EvalSummarizer() {
    }

    /**
     * 聚合报告摘要。成本口径：{@code totalTokens × pricePerKToken ÷ 1000}（元）；
     * 未配置单价时 cost 记为 0，仅做 token 统计。
     */
    public static EvalSummary summarize(EvalReport report, EvalConfig config) {
        List<CaseResult> cases = report.getCases();
        EvalSummary s = new EvalSummary();
        s.setTotal(cases.size());
        int passed = 0;
        long durationSum = 0;
        long tokenSum = 0;
        for (CaseResult c : cases) {
            if (c.isPassed()) {
                passed++;
            }
            durationSum += c.getDurationMs();
            tokenSum += c.getTokens();
        }
        s.setPassed(passed);
        s.setFailed(cases.size() - passed);
        s.setPassRate(cases.isEmpty() ? 0D : (double) passed / cases.size());
        s.setAvgDurationMs(cases.isEmpty() ? 0D : (double) durationSum / cases.size());
        s.setAvgTokens(cases.isEmpty() ? 0D : (double) tokenSum / cases.size());
        s.setTotalTokens(tokenSum);
        Double price = config == null ? null : config.getPricePerKToken();
        s.setCost(price == null ? 0D : tokenSum * price / 1000D);
        return s;
    }
}