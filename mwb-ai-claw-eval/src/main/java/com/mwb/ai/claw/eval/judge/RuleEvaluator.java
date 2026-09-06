package com.mwb.ai.claw.eval.judge;

import java.util.regex.Pattern;

import com.mwb.ai.claw.eval.model.EvalCase;
import com.mwb.ai.claw.eval.model.EvalRule;
import com.mwb.ai.claw.eval.model.JudgeType;
import com.mwb.ai.claw.eval.model.RuleType;

/**
 * 确定性规则判定器（type=rule）：对 Agent 输出做零 LLM 成本的快速判别。
 * <p>
 * 支持三种规则（见 {@link RuleType}）：{@code exact} 逐字符精确匹配、{@code contains} 子串包含、
 * {@code regex} 正则匹配。命中即 pass（score=1），未命中 fail（score=0）。
 * <p>
 * 用例未配置规则（{@code EvalCase.rule} 为空）或规则不完整时返回 {@code null}，
 * 由调度方回退至 LLM 裁判。
 */
public class RuleEvaluator implements Evaluator {

    @Override
    public JudgeType type() {
        return JudgeType.RULE;
    }

    @Override
    public JudgeVerdict judge(EvalCase c, String reply) {
        EvalRule rule = c == null ? null : c.getRule();
        if (rule == null || rule.getValue() == null || rule.getType() == null) {
            // 无可审判的规则：交由调度方回退其他判定
            return null;
        }
        boolean matched = match(rule.getType(), rule.getValue(), rule.isIgnoreCase(),
                reply == null ? "" : reply);
        if (matched) {
            return JudgeVerdict.pass(1D, buildVerdict("命中规则", rule, c), JudgeType.RULE);
        }
        return JudgeVerdict.fail(0D, buildVerdict("未命中规则", rule, c), JudgeType.RULE);
    }

    private boolean match(RuleType type, String value, boolean ignoreCase, String reply) {
        String target = reply;
        String pattern = value;
        if (ignoreCase) {
            target = reply.toLowerCase();
            pattern = value.toLowerCase();
        }
        switch (type) {
            case EXACT:
                return target.equals(pattern);
            case CONTAINS:
                return target.contains(pattern);
            case REGEX:
                return Pattern.compile(value).matcher(target).find();
            default:
                throw new IllegalArgumentException("不支持的规则类型: " + type);
        }
    }

    private String buildVerdict(String action, EvalRule rule, EvalCase c) {
        return action + "[type=" + rule.getType() + ", value='" + rule.getValue() + "'] case=" + c.getId();
    }
}