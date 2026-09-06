package com.mwb.ai.claw.eval.judge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.eval.model.EvalCase;
import com.mwb.ai.claw.eval.model.JudgeReplyMode;
import com.mwb.ai.claw.eval.model.JudgeType;

public class LlmJudgeEvaluatorTest {

    private ModelConfig judgeConfig() {
        ModelConfig mc = new ModelConfig();
        mc.setModel("judge-small");
        mc.setMaxTokens(512);
        return mc;
    }

    @Test
    public void judge_booleanPassed() {
        EvalCase c = new EvalCase();
        c.setId("c1");
        c.setPrompt("1+1=?");
        c.setExpected("2");
        c.setMode(JudgeReplyMode.BOOLEAN);

        LlmJudgeEvaluator e = new LlmJudgeEvaluator(fakeGateway("{\"passed\":true,\"score\":1,\"verdict\":\"答案正确\"}"),
                judgeConfig());
        JudgeVerdict v = e.judge(c, "2");

        assertNotNull(v);
        assertEquals(JudgeType.LLM, v.getJudge());
        assertTrue(v.isPassed());
        assertEquals(Double.valueOf(1D), v.getScore());
    }

    @Test
    public void judge_booleanFailed() {
        EvalCase c = new EvalCase();
        c.setId("c2");
        c.setPrompt("2+2=?");
        c.setExpected("4");
        c.setMode(JudgeReplyMode.BOOLEAN);

        LlmJudgeEvaluator e = new LlmJudgeEvaluator(fakeGateway("{\"passed\":false,\"score\":0,\"verdict\":\"答案错误\"}"),
                judgeConfig());
        assertFalse(e.judge(c, "5").isPassed());
    }

    @Test
    public void judge_reasoningDefaultMode() {
        EvalCase c = new EvalCase();
        c.setId("c3");
        c.setPrompt("介绍一下 RAG");
        c.setExpected("检索增强生成");

        // 未指定 mode → 默认 boolean 引导
        LlmJudgeEvaluator e = new LlmJudgeEvaluator(fakeGateway("{\"passed\":true,\"score\":1,\"verdict\":\"达标\"}"),
                judgeConfig());
        JudgeVerdict v = e.judge(c, "RAG = 检索增强生成");
        assertTrue(v.isPassed());
        assertEquals(JudgeType.LLM, v.getJudge());
    }

    @Test
    public void judge_fencedJson_tolerated() {
        EvalCase c = new EvalCase();
        c.setId("c4");
        c.setPrompt("p");
        c.setExpected("e");

        LlmJudgeEvaluator e = new LlmJudgeEvaluator(
                fakeGateway("```json\n{\"passed\":true,\"score\":1,\"verdict\":\"ok\"}\n```"),
                judgeConfig());
        JudgeVerdict v = e.judge(c, "r");
        assertTrue(v.isPassed());
    }

    @Test
    public void judge_invalidJson_yieldsFail() {
        EvalCase c = new EvalCase();
        c.setId("c5");
        c.setPrompt("p");
        c.setExpected("e");

        LlmJudgeEvaluator e = new LlmJudgeEvaluator(fakeGateway("not-json at all"), judgeConfig());
        JudgeVerdict v = e.judge(c, "r");
        assertFalse(v.isPassed());
        assertNotNull(v.getVerdict());
        assertTrue(v.getVerdict().contains("裁判异常") || v.getVerdict().contains("JSON"));
    }

    @Test
    public void judge_numberMode_scoreParsed() {
        EvalCase c = new EvalCase();
        c.setId("c6");
        c.setPrompt("p");
        c.setExpected("e");
        c.setMode(JudgeReplyMode.NUMBER);

        LlmJudgeEvaluator e = new LlmJudgeEvaluator(fakeGateway("{\"passed\":true,\"score\":8,\"verdict\":\"较好\"}"),
                judgeConfig());
        JudgeVerdict v = e.judge(c, "r");
        assertTrue(v.isPassed());
        assertEquals(Double.valueOf(8D), v.getScore());
    }

    @Test
    public void judge_nullPrompt_returnsNull() {
        EvalCase c = new EvalCase();
        c.setId("c7");
        LlmJudgeEvaluator e = new LlmJudgeEvaluator(fakeGateway("x"), judgeConfig());
        assertNull(e.judge(c, "r"));
    }

    // ---------- fake ----------

    private static LlmGateway fakeGateway(final String content) {
        return new LlmGateway() {
            @Override
            public LlmResponse chat(LlmRequest request, ModelConfig modelConfig) {
                LlmResponse r = new LlmResponse();
                r.setContent(content);
                r.setFinishReason("stop");
                r.setPromptTokens(10);
                r.setCompletionTokens(5);
                return r;
            }

            @Override
            public LlmResponse streamChat(LlmRequest request, ModelConfig modelConfig, LlmStreamCallback callback) {
                if (callback != null) {
                    callback.onToken(content);
                }
                return chat(request, modelConfig);
            }
        };
    }
}