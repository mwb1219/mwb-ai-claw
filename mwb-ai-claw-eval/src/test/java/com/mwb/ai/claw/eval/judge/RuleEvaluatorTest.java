package com.mwb.ai.claw.eval.judge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import com.mwb.ai.claw.eval.model.EvalCase;
import com.mwb.ai.claw.eval.model.EvalRule;
import com.mwb.ai.claw.eval.model.JudgeType;
import com.mwb.ai.claw.eval.model.RuleType;

@RunWith(Parameterized.class)
public class RuleEvaluatorTest {

    private final RuleEvaluator evaluator = new RuleEvaluator();

    private final RuleType type;
    private final String value;
    private final boolean ignoreCase;
    private final String reply;
    private final boolean expectPass;

    public RuleEvaluatorTest(RuleType type, String value, boolean ignoreCase, String reply, boolean expectPass) {
        this.type = type;
        this.value = value;
        this.ignoreCase = ignoreCase;
        this.reply = reply;
        this.expectPass = expectPass;
    }

    @Parameterized.Parameters(name = "{0} value='{1}' ignoreCase={2} reply='{3}' => pass={4}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                // contains
                {RuleType.CONTAINS, "2", false, "答案是 2", true},
                {RuleType.CONTAINS, "42", false, "答案是 2", false},
                {RuleType.CONTAINS, "http", true, "参考 https://example.com 文档", true},
                {RuleType.CONTAINS, "HTTP", false, "参考 http 协议", false},
                // exact
                {RuleType.EXACT, "2", false, "2", true},
                {RuleType.EXACT, "2", false, "答案是 2", false},
                {RuleType.EXACT, "yes", true, "YES", true},
                // regex
                {RuleType.REGEX, "检\\S{0,20}生\\S{0,20}", false, "检索召回之后再生成", true},
                {RuleType.REGEX, "\\d+\\.\\d+", false, "失败次数 3.5 次", true},
                {RuleType.REGEX, "\\d{4}-\\d{2}-\\d{2}", false, "今天的日期", false},
        });
    }

    @Test
    public void judge_matchesRuleAsExpected() {
        EvalCase c = new EvalCase();
        c.setId("c1");
        EvalRule rule = new EvalRule();
        rule.setType(type);
        rule.setValue(value);
        rule.setIgnoreCase(ignoreCase);
        c.setRule(rule);

        JudgeVerdict v = evaluator.judge(c, reply);

        assertEquals(JudgeType.RULE, v.getJudge());
        assertEquals(expectPass, v.isPassed());
        // 确定性规则得分恒为 0/1
        assertEquals(Double.valueOf(expectPass ? 1D : 0D), v.getScore());
    }

    @Test
    public void judge_noRule_returnsNull() {
        EvalCase c = new EvalCase();
        c.setId("c2");
        assertNull(evaluator.judge(c, "any reply"));
    }

    @Test
    public void judge_ruleWithoutValue_returnsNull() {
        EvalCase c = new EvalCase();
        c.setId("c3");
        EvalRule rule = new EvalRule();
        rule.setType(RuleType.CONTAINS);
        c.setRule(rule);
        assertNull(evaluator.judge(c, "any reply"));
    }

    @Test
    public void judge_nullReply_treatedAsEmpty_YieldsFail() {
        EvalCase c = new EvalCase();
        c.setId("c4");
        EvalRule rule = new EvalRule();
        rule.setType(RuleType.CONTAINS);
        rule.setValue("expected");
        c.setRule(rule);

        JudgeVerdict v = evaluator.judge(c, null);
        assertFalse(v.isPassed());
    }
}