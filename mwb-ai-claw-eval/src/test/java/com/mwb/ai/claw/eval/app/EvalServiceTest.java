package com.mwb.ai.claw.eval.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.mwb.ai.claw.domain.collaboration.spi.ExecutionUnit;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.eval.dataset.DatasetLoader;
import com.mwb.ai.claw.eval.model.CaseResult;
import com.mwb.ai.claw.eval.model.DiffStatus;
import com.mwb.ai.claw.eval.model.EvalConfig;
import com.mwb.ai.claw.eval.model.EvalDiff;
import com.mwb.ai.claw.eval.model.EvalReport;
import com.mwb.ai.claw.eval.report.ReportWriter;
import com.mwb.ai.claw.eval.runner.EvalRunner;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * EvalService 单元测试：覆盖 run（执行 + 落盘可回读）、diff（回归对比）、listDatasets（含解析失败标注）。
 * 判定统一走 rule（避免依赖 LLM），EvalRunner 以 fake 执行单元注入。
 */
public class EvalServiceTest {

    private Path tmp;

    @Before
    public void setUp() throws Exception {
        tmp = Files.createTempDirectory("eval-service");
    }

    // ==================== 测试替身 ====================

    static class FakeExecutionUnit implements ExecutionUnit {
        final Map<String, String> replies = new HashMap<>();
        final FakeMetricsRecorder metrics;

        FakeExecutionUnit(FakeMetricsRecorder metrics) {
            this.metrics = metrics;
        }

        @Override
        public String runAgent(String prompt, Agent agent, com.mwb.ai.claw.domain.core.ProgressCallback callback,
                               LlmStreamCallback streamCallback) {
            metrics.tokens += 50;
            return replies.getOrDefault(prompt, "");
        }

        @Override
        public com.mwb.ai.claw.domain.core.Session getOrCreateSession(AgentScope scope, String sessionId, Agent agent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveSession(com.mwb.ai.claw.domain.core.Session session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.mwb.ai.claw.domain.core.ReActResult runSession(
                com.mwb.ai.claw.domain.core.Session session, Agent agent,
                com.mwb.ai.claw.domain.core.ProgressCallback callback, LlmStreamCallback streamCallback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.nio.file.Path writeArtifact(String workdir, String stageId, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.nio.file.Path writeFile(String dir, String fileName, String content) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.mwb.ai.claw.domain.collaboration.model.CollaborationResult runOrchestration(
                AgentScope scope, String message, String orchestrationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void executeWithSessionLock(AgentScope scope, String sessionId, Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T executeWithSessionLock(AgentScope scope, String sessionId, java.util.function.Supplier<T> task) {
            throw new UnsupportedOperationException();
        }
    }

    static class FakeMetricsRecorder extends MetricsRecorder {
        long tokens;

        FakeMetricsRecorder() {
            super(new SimpleMeterRegistry());
        }

        @Override
        public List<Map<String, Object>> snapshot() {
            Map<String, Object> m = new HashMap<>();
            m.put("name", "claw.llm.token");
            m.put("count", tokens);
            return java.util.Collections.singletonList(m);
        }
    }

    static class FakeAgentGateway implements AgentGateway {
        private final Agent agent;

        FakeAgentGateway(Agent agent) {
            this.agent = agent;
        }

        @Override
        public Agent getAgent(String agentId) {
            return agent;
        }

        @Override
        public List<Agent> listAgents() {
            return java.util.Collections.singletonList(agent);
        }
    }

    // ==================== 工具方法 ====================

    private Path writeDataset(String content) throws IOException {
        Path f = tmp.resolve("ds.json");
        Files.write(f, content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private EvalConfig config(String datasetPath, Path output) {
        EvalConfig cfg = new EvalConfig();
        cfg.setDatasetPath(datasetPath);
        cfg.setAgentId("main");
        cfg.setJudge("rule");
        cfg.setOutput(output.toString());
        return cfg;
    }

    private EvalService service(Agent agent, Map<String, String> replies) {
        FakeMetricsRecorder metrics = new FakeMetricsRecorder();
        FakeExecutionUnit exec = new FakeExecutionUnit(metrics);
        exec.replies.putAll(replies);
        EvalRunner runner = new EvalRunner(exec, new FakeAgentGateway(agent), metrics,
                AgentScope.defaultScope(), null);
        return new EvalService(runner, new DatasetLoader(), new ReportWriter());
    }

    private Agent agent() {
        Agent a = new Agent();
        a.setAgentId("main");
        ModelConfig mc = new ModelConfig();
        mc.setModel("agent-model");
        a.setModelConfig(mc);
        return a;
    }

    // 单数据集：c1 期望含"2"（contains 规则）
    private String oneCaseJson() {
        return "{\"task\":{\"id\":\"qa-basic\",\"name\":\"基础问答\",\"defaultJudge\":\"rule\"},"
                + "\"cases\":[{\"id\":\"c1\",\"prompt\":\"q\",\"expected\":\"答案含2\","
                + "\"rule\":{\"type\":\"contains\",\"value\":\"2\"}}]}";
    }

    // ==================== 测试用例 ====================

    @Test
    public void run_writesReport_readableByLoadReport() throws Exception {
        Path ds = writeDataset(oneCaseJson());
        Path out = tmp.resolve("out");
        EvalService svc = service(agent(), java.util.Collections.singletonMap("q", "答案是 2"));

        EvalRunResult result = svc.run(config(ds.toString(), out));

        assertNotNull(result.getReport());
        assertTrue(result.getReport().getCases().get(0).isPassed());
        assertTrue(Files.exists(java.nio.file.Paths.get(result.getJsonPath())));
        assertTrue("JSON 报告应写于输出目录", result.getJsonPath().contains(out.toString()));
        assertTrue(Files.exists(java.nio.file.Paths.get(result.getMdPath())));
        // 回读 JSON 报告与运行结果一致
        EvalReport loaded = svc.loadReport(result.getJsonPath());
        assertEquals("qa-basic", loaded.getTaskId());
        assertEquals(1.0, loaded.getSummary().getPassRate(), 1e-6);
        assertEquals("agent-model", loaded.getMeta().getModel());
    }

    @Test
    public void diff_marksRegressionAndImprovement() throws Exception {
        Path ds = writeDataset(oneCaseJson());
        Path outBase = tmp.resolve("base");
        Path outCur = tmp.resolve("cur");

        // baseline：c1 通过
        EvalService baseSvc = service(agent(), java.util.Collections.singletonMap("q", "答案是 2"));
        EvalRunResult base = baseSvc.run(config(ds.toString(), outBase));
        // current：c1 失败
        EvalService curSvc = service(agent(), java.util.Collections.singletonMap("q", "我不知道"));
        EvalRunResult cur = curSvc.run(config(ds.toString(), outCur));

        EvalDiff diff = new EvalService(null, null, null).diff(base.getJsonPath(), cur.getJsonPath());

        assertTrue(diff.isRegression());
        assertEquals(1, diff.getRegressedCount());
        assertEquals(0, diff.getImprovedCount());
        assertEquals(1.0, diff.getBaselinePassRate(), 1e-6);
        assertEquals(0.0, diff.getCurrentPassRate(), 1e-6);
        assertEquals(DiffStatus.REGRESSED, diff.getItems().get(0).getStatus());
        assertTrue(diff.getItems().get(0).isBaselinePassed());
        assertFalse(diff.getItems().get(0).isCurrentPassed());
    }

    @Test
    public void listDatasets_listsValidAndMarksFailed() throws Exception {
        writeDataset(oneCaseJson()); // 合法数据集
        Path bad = tmp.resolve("bad.yaml");
        Files.write(bad, "task: {name: x}\ncases: []\n".getBytes(StandardCharsets.UTF_8)); // 缺 task.id

        List<DatasetInfo> infos = new EvalService(null, new DatasetLoader(), null).listDatasets(tmp.toString());

        assertEquals(2, infos.size());
        DatasetInfo valid = infos.stream().filter(DatasetInfo::isLoaded).findFirst().orElse(null);
        DatasetInfo failed = infos.stream().filter(i -> !i.isLoaded()).findFirst().orElse(null);
        assertNotNull(valid);
        assertNotNull(failed);
        assertEquals("qa-basic", valid.getTaskId());
        assertEquals(1, valid.getCaseCount());
        assertTrue(failed.getError() != null && !failed.getError().isEmpty());
    }

    @Test
    public void run_missingDatasetPath_throws() throws Exception {
        EvalService svc = service(agent(), java.util.Collections.singletonMap("q", "2"));
        try {
            svc.run(config("", tmp.resolve("out")));
            fail("应因缺少 datasetPath 抛异常");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("datasetPath"));
        }
    }

    @Test
    public void diff_sameReport_noRegression() throws Exception {
        Path ds = writeDataset(oneCaseJson());
        Path out1 = tmp.resolve("r1");
        Path out2 = tmp.resolve("r2");
        EvalService svc = service(agent(), java.util.Collections.singletonMap("q", "答案是 2"));
        EvalRunResult r1 = svc.run(config(ds.toString(), out1));
        EvalRunResult r2 = svc.run(config(ds.toString(), out2));

        EvalDiff diff = new EvalService(null, null, null).diff(r1.getJsonPath(), r2.getJsonPath());

        assertFalse(diff.isRegression());
        assertEquals(DiffStatus.UNCHANGED, diff.getItems().get(0).getStatus());
    }
}