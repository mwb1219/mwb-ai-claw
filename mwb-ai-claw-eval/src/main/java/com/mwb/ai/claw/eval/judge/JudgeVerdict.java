package com.mwb.ai.claw.eval.judge;

import com.mwb.ai.claw.eval.model.JudgeType;
import lombok.Data;

/**
 * 一次判定的结果：通过与否 + 得分 + 判定原文/理由 + 所用判定方式。
 * <p>
 * 规则判定得分恒为 0/1；LLM 裁判得分由裁判返回（0-10 或 0-1，见 {@code JudgeReplyMode}）。
 */
@Data
public class JudgeVerdict {

    /** 是否通过 */
    private final boolean passed;

    /** 得分（可为 null，如 boolean 判定不产分） */
    private final Double score;

    /** 判定原文 / 理由 */
    private final String verdict;

    /** 实际采用的判定方式 */
    private final JudgeType judge;

    public JudgeVerdict(boolean passed, Double score, String verdict, JudgeType judge) {
        this.passed = passed;
        this.score = score;
        this.verdict = verdict;
        this.judge = judge;
    }

    /** 通过判定（规则命中或 LLM 裁判 pass）。 */
    public static JudgeVerdict pass(Double score, String verdict, JudgeType judge) {
        return new JudgeVerdict(true, score, verdict, judge);
    }

    /** 失败判定。 */
    public static JudgeVerdict fail(Double score, String verdict, JudgeType judge) {
        return new JudgeVerdict(false, score, verdict, judge);
    }
}