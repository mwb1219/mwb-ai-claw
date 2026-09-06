package com.mwb.ai.claw.eval.report;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

import com.mwb.ai.claw.eval.model.CaseResult;
import com.mwb.ai.claw.eval.model.EvalConfig;
import com.mwb.ai.claw.eval.model.EvalReport;
import com.mwb.ai.claw.eval.model.EvalSummary;

public class EvalSummarizerTest {

    private CaseResult caseResult(boolean passed, long durationMs, long tokens) {
        CaseResult c = new CaseResult();
        c.setPassed(passed);
        c.setDurationMs(durationMs);
        c.setTokens(tokens);
        return c;
    }

    @Test
    public void summarize_computesPassRateLatencyAndCost() {
        EvalReport report = new EvalReport();
        report.setCases(Arrays.asList(
                caseResult(true, 100L, 500L),
                caseResult(true, 300L, 700L),
                caseResult(false, 200L, 300L)));

        EvalConfig config = new EvalConfig();
        config.setPricePerKToken(2D); // 元/千 token

        EvalSummary s = EvalSummarizer.summarize(report, config);

        assertEquals(3, s.getTotal());
        assertEquals(2, s.getPassed());
        assertEquals(1, s.getFailed());
        assertEquals(2D / 3D, s.getPassRate(), 1e-6);
        assertEquals(200D, s.getAvgDurationMs(), 1e-6);
        assertEquals(500D, s.getAvgTokens(), 1e-6);
        assertEquals(1500L, s.getTotalTokens());
        // 1500 token × 2元/千 token = 3 元
        assertEquals(3D, s.getCost(), 1e-6);
    }

    @Test
    public void summarize_noPrice_onlyCountsTokens() {
        EvalReport report = new EvalReport();
        report.setCases(Arrays.asList(caseResult(true, 10L, 1000L)));
        EvalConfig config = new EvalConfig(); // price null

        EvalSummary s = EvalSummarizer.summarize(report, config);

        assertEquals(1000L, s.getTotalTokens());
        assertEquals(0D, s.getCost(), 1e-6);
        assertEquals(1.0, s.getPassRate(), 1e-6);
    }

    @Test
    public void summarize_emptyCases_noDivisionByZero() {
        EvalReport report = new EvalReport();
        EvalSummary s = EvalSummarizer.summarize(report, new EvalConfig());
        assertEquals(0, s.getTotal());
        assertEquals(0, s.getPassed());
        assertEquals(0.0, s.getPassRate(), 1e-6);
        assertEquals(0.0, s.getAvgDurationMs(), 1e-6);
    }
}