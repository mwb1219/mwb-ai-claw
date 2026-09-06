package com.mwb.ai.claw.eval.maven;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.Before;
import org.junit.Test;

import com.mwb.ai.claw.eval.model.CaseResult;
import com.mwb.ai.claw.eval.model.EvalReport;
import com.mwb.ai.claw.eval.model.EvalSummary;
import com.mwb.ai.claw.eval.report.ReportWriter;

/**
 * DiffMojo 单元测试：回归 + failOnRegression=true 抛构建失败；=false 仅告警；无回归不抛。
 */
public class DiffMojoTest {

    private Path tmp;
    private final ReportWriter writer = new ReportWriter();

    @Before
    public void setUp() throws Exception {
        tmp = Files.createTempDirectory("eval-mojo");
    }

    // ==================== 报告构造 ====================

    private EvalReport report(boolean passed) {
        EvalReport r = new EvalReport();
        r.setTaskId("qa-basic");
        r.setTaskName("基础问答");
        r.setRunAt(System.currentTimeMillis());
        CaseResult c = new CaseResult();
        c.setCaseId("c1");
        c.setName("c1");
        c.setPassed(passed);
        r.setCases(Collections.singletonList(c));
        EvalSummary s = new EvalSummary();
        s.setTotal(1);
        s.setPassed(passed ? 1 : 0);
        s.setFailed(passed ? 0 : 1);
        s.setPassRate(passed ? 1.0 : 0.0);
        r.setSummary(s);
        return r;
    }

    private String writeReport(boolean passed) throws Exception {
        Path dir = tmp.resolve(passed ? "base" : "cur");
        Files.createDirectories(dir);
        return writer.writeJson(report(passed), dir).toString();
    }

    private DiffMojo mojo(String baseline, String current, boolean failOnRegression) throws Exception {
        DiffMojo m = new DiffMojo();
        setField(m, "baseline", baseline);
        setField(m, "current", current);
        setField(m, "failOnRegression", failOnRegression);
        m.setLog(new SystemStreamLog());
        return m;
    }

    private void setField(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    // ==================== 测试用例 ====================

    @Test
    public void regressionWithFailOnRegressionTrue_failsBuild() throws Exception {
        String base = writeReport(true);
        String cur = writeReport(false);

        DiffMojo m = mojo(base, cur, true);

        MojoFailureException ex = assertThrows(MojoFailureException.class, m::execute);
        assertTrue(ex.getMessage().contains("回归"));
    }

    @Test
    public void regressionWithFailOnRegressionFalse_doesNotFail() throws Exception {
        String base = writeReport(true);
        String cur = writeReport(false);

        DiffMojo m = mojo(base, cur, false);

        m.execute(); // 不抛 MojoFailureException
    }

    @Test
    public void noRegression_doesNotFail() throws Exception {
        String base = writeReport(true);
        String cur = writeReport(true);

        DiffMojo m = mojo(base, cur, true);

        m.execute(); // 无回归，成功
    }

    @Test
    public void improvement_doesNotFail() throws Exception {
        String base = writeReport(false);
        String cur = writeReport(true);

        DiffMojo m = mojo(base, cur, true);

        m.execute(); // 通过率提升，不视为回归
    }

    @Test
    public void report_reportMojoLoadsAndLogs() throws Exception {
        String report = writeReport(true);

        ReportMojo rm = new ReportMojo();
        setField(rm, "report", report);
        rm.setLog(new SystemStreamLog());

        rm.execute(); // 不抛 MojoExecutionException
        assertTrue(Files.exists(java.nio.file.Paths.get(report)));
    }
}