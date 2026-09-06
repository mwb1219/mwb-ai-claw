package com.mwb.ai.claw.eval.runner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.mwb.ai.claw.domain.collaboration.spi.ExecutionUnit;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.core.ReActResult;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.observability.TraceRun;
import com.mwb.ai.claw.domain.observability.TraceStep;
import com.mwb.ai.claw.domain.observability.TraceStore;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.eval.judge.LlmJudgeEvaluator;
import com.mwb.ai.claw.eval.model.CaseResult;
import com.mwb.ai.claw.eval.model.EvalCase;
import com.mwb.ai.claw.eval.model.EvalConfig;
import com.mwb.ai.claw.eval.model.EvalDataset;
import com.mwb.ai.claw.eval.model.EvalReport;
import com.mwb.ai.claw.eval.model.EvalRule;
import com.mwb.ai.claw.eval.model.EvalTask;
import com.mwb.ai.claw.eval.model.JudgeType;
import com.mwb.ai.claw.eval.model.RuleType;
import com.mwb.ai.claw.eval.model.TraceDiffStatus;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * EvalRunner 单元测试：覆盖执行、token/耗时度量、判定策略（rule / llm / both）与执行异常回退。
 */
public class EvalRunnerTest {

    // ==================== 测试替身 ====================

    /** 可编程回复/异常的 ExecutionUnit（只用到 runAgent）。 */
    static class FakeExecutionUnit implements ExecutionUnit {
        final Map<String, String> replies = new HashMap<>();
        final Map<String, RuntimeException> errors = new HashMap<>();
        final Map<String, List<String>> traces = new HashMap<>();
        final FakeMetricsRecorder metrics;
        int llmTokenPerCall = 100; // 每次 runAgent 累加的 token 增量

        FakeExecutionUnit(FakeMetricsRecorder metrics) {
            this.metrics = metrics;
        }

        @Override
        public String runAgent(String prompt, Agent agent, com.mwb.ai.claw.domain.core.ProgressCallback callback,
                               LlmStreamCallback streamCallback) {
            if (errors.containsKey(prompt)) {
                throw errors.get(prompt);
            }
            metrics.tokens += llmTokenPerCall; // 模拟该 case 消耗 token
            return replies.getOrDefault(prompt, "");
        }

        @Override
        public ReActResult runAgentResult(String prompt, Agent agent,
                                          com.mwb.ai.claw.domain.core.ProgressCallback callback,
                                          LlmStreamCallback streamCallback) {
            if (errors.containsKey(prompt)) {
                throw errors.get(prompt);
            }
            metrics.tokens += llmTokenPerCall;
            ReActResult r = new ReActResult();
            r.setReply(replies.getOrDefault(prompt, ""));
            r.setSuccess(true);
            if (traces.containsKey(prompt)) {
                r.setTraceSteps(new ArrayList<>(traces.get(prompt)));
            }
            return r;
        }

        // 其余方法不支持，抛出 UnsupportedOperationException
        @Override
        public Session getOrCreateSession(AgentScope scope, String sessionId, Agent agent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void saveSession(Session session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ReActResult runSession(Session session, Agent agent,
                                      com.mwb.ai.claw.domain.core.ProgressCallback callback,
                                      LlmStreamCallback streamCallback) {
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

    /** 可控 token 计数的 MetricsRecorder（重写 snapshot 暴露累计 token）。 */
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
            return Collections.singletonList(m);
        }
    }

    /** 仅返回指定 Agent 的 AgentGateway。 */
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
            return Collections.singletonList(agent);
        }
    }

    /** 内存版 TraceStore：按 traceId 存/取（用于验证 golden baseline 落库与对比）。 */
    static class FakeTraceStore implements TraceStore {
        final Map<String, TraceRun> store = new HashMap<>();

        @Override
        public void saveTrace(TraceRun trace) {
            store.put(trace.getTraceId(), trace);
        }

        @Override
        public TraceRun findTrace(AgentScope scope, String traceId) {
            return store.get(traceId);
        }
    }

    /** 返回固定 JSON 的 LlmGateway，供 LlmJudgeEvaluator 判分。 */
    static LlmGateway fakeLlm(final String json) {
        return new LlmGateway() {
            @Override
            public LlmResponse chat(LlmRequest request, ModelConfig modelConfig) {
                LlmResponse r = new LlmResponse();
                r.setContent(json);
                r.setFinishReason("stop");
                r.setPromptTokens(5);
                r.setCompletionTokens(5);
                return r;
            }

            @Override
            public LlmResponse streamChat(LlmRequest request, ModelConfig modelConfig, LlmStreamCallback callback) {
                if (callback != null) {
                    callback.onToken(json);
                }
                return chat(request, modelConfig);
            }
        };
    }

    // ==================== 工具方法 ====================

    private Agent agent() {
        Agent a = new Agent();
        a.setAgentId("main");
        ModelConfig mc = new ModelConfig();
        mc.setModel("agent-model");
        a.setModelConfig(mc);
        return a;
    }

    private EvalCase ruleCase(String id, String prompt, String expected, RuleType type, String value) {
        EvalCase c = new EvalCase();
        c.setId(id);
        c.setName("case-" + id);
        c.setPrompt(prompt);
        c.setExpected(expected);
        EvalRule r = new EvalRule();
        r.setType(type);
        r.setValue(value);
        c.setRule(r);
        return c;
    }

    private EvalCase plainCase(String id, String prompt, String expected) {
        EvalCase c = new EvalCase();
        c.setId(id);
        c.setName("case-" + id);
        c.setPrompt(prompt);
        c.setExpected(expected);
        return c;
    }

    private EvalDataset dataset(List<EvalCase> cases) {
        EvalTask task = new EvalTask();
        task.setId("ds");
        task.setName("ds-name");
        task.setVersion("1.0");
        EvalDataset ds = new EvalDataset();
        ds.setTask(task);
        ds.setCases(cases);
        return ds;
    }

    private EvalConfig config(String judge) {
        EvalConfig cfg = new EvalConfig();
        cfg.setAgentId("main");
        cfg.setJudge(judge);
        return cfg;
    }

    // ==================== 测试用例 ====================

    @Test
    public void run_ruleStrategy_judgesByRuleAndMeasuresTokens() {
        FakeMetricsRecorder metrics = new FakeMetricsRecorder();
        FakeExecutionUnit exec = new FakeExecutionUnit(metrics);
        exec.replies.put("q1", "答案是 2");
        exec.replies.put("q2", "我不知道");

        EvalRunner runner = new EvalRunner(exec, new FakeAgentGateway(agent()), metrics,
                AgentScope.defaultScope(), null);

        EvalDataset ds = dataset(java.util.Arrays.asList(
                ruleCase("c1", "q1", "答案含2", RuleType.CONTAINS, "2"),
                ruleCase("c2", "q2", "答案含2", RuleType.CONTAINS, "2")));

        EvalReport report = runner.run(config("rule"), ds);

        assertEquals(2, report.getCases().size());
        assertEquals(JudgeType.RULE, report.getCases().get(0).getJudge());
        assertTrue(report.getCases().get(0).isPassed());
        assertFalse(report.getCases().get(1).isPassed());
        // 每个 case 消耗 100 token
        assertEquals(200, report.getSummary().getTotalTokens());
        assertEquals(1, report.getSummary().getPassed());
        assertEquals(1, report.getSummary().getFailed());
        assertEquals(0.5, report.getSummary().getPassRate(), 1e-6);
        // meta 记录执行模型
        assertEquals("agent-model", report.getMeta().getModel());
        assertEquals("ds", report.getTaskId());
    }

    @Test
    public void run_llmStrategy_usesLlmJudge() {
        FakeMetricsRecorder metrics = new FakeMetricsRecorder();
        FakeExecutionUnit exec = new FakeExecutionUnit(metrics);
        exec.replies.put("q open", "开源框架，支持自定义扩展");

        LlmJudgeEvaluator llmJudge = new LlmJudgeEvaluator(
                fakeLlm("{\"passed\":true,\"score\":1,\"verdict\":\"回答达标\"}"), judgeConfig());

        EvalRunner runner = new EvalRunner(exec, new FakeAgentGateway(agent()), metrics,
                AgentScope.defaultScope(), llmJudge);

        EvalReport report = runner.run(config("llm"),
                dataset(Collections.singletonList(plainCase("c1", "q open", "期望要点"))));

        CaseResult cr = report.getCases().get(0);
        assertEquals(JudgeType.LLM, cr.getJudge());
        assertTrue(cr.isPassed());
        assertEquals(Double.valueOf(1D), cr.getScore());
        assertEquals("回答达标", cr.getVerdict());
    }

    @Test
    public void run_bothStrategy_ruleHitShortCircuits_ruleMissFallsBackToLlm() {
        FakeMetricsRecorder metrics = new FakeMetricsRecorder();
        FakeExecutionUnit exec = new FakeExecutionUnit(metrics);
        exec.replies.put("q rule", "命中 2");
        exec.replies.put("q open", "开放题输出");

        // LLM 裁判对开放题返回通过
        LlmJudgeEvaluator llmJudge = new LlmJudgeEvaluator(
                fakeLlm("{\"passed\":true,\"score\":1,\"verdict\":\"语义达标\"}"), judgeConfig());

        EvalRunner runner = new EvalRunner(exec, new FakeAgentGateway(agent()), metrics,
                AgentScope.defaultScope(), llmJudge);

        List<EvalCase> cases = new ArrayList<>();
        // 有规则 → RULE 命中短路
        cases.add(ruleCase("c1", "q rule", "x", RuleType.CONTAINS, "2"));
        // 无规则 → 回退 LLM
        cases.add(plainCase("c2", "q open", "某种要点"));

        EvalReport report = runner.run(config("both"), dataset(cases));

        assertEquals(JudgeType.RULE, report.getCases().get(0).getJudge());
        assertTrue(report.getCases().get(0).isPassed());
        assertEquals(JudgeType.LLM, report.getCases().get(1).getJudge());
        assertTrue(report.getCases().get(1).isPassed());
    }

    @Test
    public void run_executionError_recordsErrorAndFails() {
        FakeMetricsRecorder metrics = new FakeMetricsRecorder();
        FakeExecutionUnit exec = new FakeExecutionUnit(metrics);
        exec.errors.put("boom", new RuntimeException("LLM 超时"));

        EvalRunner runner = new EvalRunner(exec, new FakeAgentGateway(agent()), metrics,
                AgentScope.defaultScope(), null);

        EvalReport report = runner.run(config("rule"),
                dataset(Collections.singletonList(ruleCase("c1", "boom", "x", RuleType.CONTAINS, "2"))));

        CaseResult cr = report.getCases().get(0);
        assertFalse(cr.isPassed());
        assertNotNull(cr.getError());
        assertTrue(cr.getError().contains("LLM 超时"));
        assertEquals(0, report.getSummary().getPassed());
    }

    @Test
    public void run_ruleOnly_noRule_fallsBackToEquality() {
        FakeMetricsRecorder metrics = new FakeMetricsRecorder();
        FakeExecutionUnit exec = new FakeExecutionUnit(metrics);
        exec.replies.put("q", "2");

        EvalRunner runner = new EvalRunner(exec, new FakeAgentGateway(agent()), metrics,
                AgentScope.defaultScope(), null);

        List<EvalCase> cases = new ArrayList<>();
        cases.add(plainCase("same", "q", "2"));   // 相等 → 通过
        exec.replies.put("q2", "3");
        cases.add(plainCase("diff", "q2", "2"));  // 不相等 → 失败

        EvalReport report = runner.run(config("rule"), dataset(cases));

        assertTrue(report.getCases().get(0).isPassed());
        assertFalse(report.getCases().get(1).isPassed());
    }

    @Test
    public void run_recordTraces_savesGoldenBaselineAndSetsNoBaseline() {
        FakeMetricsRecorder metrics = new FakeMetricsRecorder();
        FakeExecutionUnit exec = new FakeExecutionUnit(metrics);
        exec.replies.put("q", "answer");
        exec.traces.put("q", Arrays.asList("[Thought] 思考", "[Action] 调用工具 search", "[Observation] 结果"));

        FakeTraceStore store = new FakeTraceStore();
        EvalRunner runner = new EvalRunner(exec, new FakeAgentGateway(agent()), metrics,
                AgentScope.defaultScope(), null, store);

        EvalConfig cfg = config("rule");
        cfg.setRecordTraces(true);
        EvalReport report = runner.run(cfg, dataset(Collections.singletonList(plainCase("c1", "q", "answer"))));

        CaseResult cr = report.getCases().get(0);
        assertEquals(TraceDiffStatus.NO_BASELINE, cr.getTraceDiffStatus());
        // 应已落 golden baseline（确定性 traceId）
        assertFalse("recordTraces 应落 baseline", store.store.isEmpty());
        // baseline 步骤应含关键 action
        assertTrue(store.store.values().iterator().next().getSteps().stream()
                .anyMatch(s -> "action".equals(s.getType())));
    }

    @Test
    public void run_compare_detectsTraceRegression() {
        FakeMetricsRecorder metrics = new FakeMetricsRecorder();
        FakeExecutionUnit exec = new FakeExecutionUnit(metrics);
        exec.replies.put("q", "answer");
        FakeTraceStore store = new FakeTraceStore();
        EvalRunner runner = new EvalRunner(exec, new FakeAgentGateway(agent()), metrics,
                AgentScope.defaultScope(), null, store);

        List<EvalCase> cases = Collections.singletonList(plainCase("c1", "q", "answer"));

        // 第一步：落基线（含关键 action）
        exec.traces.put("q", Arrays.asList("[Thought] 思考", "[Action] 调用工具 search", "[Observation] 结果"));
        EvalConfig rec = config("rule");
        rec.setRecordTraces(true);
        runner.run(rec, dataset(cases));

        // 第二步：对比模式，缺少关键 action → 回归
        exec.traces.put("q", Collections.singletonList("[Thought] 思考"));
        EvalReport report2 = runner.run(config("rule"), dataset(cases));
        CaseResult cr2 = report2.getCases().get(0);
        assertEquals(TraceDiffStatus.REGRESSED, cr2.getTraceDiffStatus());
        assertNotNull(cr2.getTraceDiffDetails());
    }

    private com.mwb.ai.claw.domain.core.ModelConfig judgeConfig() {
        com.mwb.ai.claw.domain.core.ModelConfig mc = new com.mwb.ai.claw.domain.core.ModelConfig();
        mc.setModel("judge-small");
        mc.setMaxTokens(512);
        return mc;
    }
}