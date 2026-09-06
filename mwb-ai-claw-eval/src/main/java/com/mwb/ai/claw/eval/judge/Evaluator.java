package com.mwb.ai.claw.eval.judge;

import com.mwb.ai.claw.eval.model.EvalCase;
import com.mwb.ai.claw.eval.model.JudgeType;

/**
 * 判定 SPI：对 {@link EvalCase}（含期望 golden answer）+ Agent 输出 {@code reply} 给出通过/失败判定。
 * <p>
 * 实现以 {@link JudgeType} 区分：
 * <ul>
 *   <li>{@link JudgeType#RULE}：确定性规则（exact/contains/regex），零 LLM 成本、秒级回归；</li>
 *   <li>{@link JudgeType#LLM}：LLM 语义裁判。</li>
 * </ul>
 * 当用例判定方式与实现类型不匹配（如 rule 实现遇到无 {@code EvalCase.rule} 的用例）时，
 * {@link #judge} 返回 {@code null}，由调度方决定回退另一判定。
 */
public interface Evaluator {

    /** 该实现支持的判定方式（rule | llm） */
    JudgeType type();

    /**
     * 对单条用例判定。
     *
     * @param c     用例（含期望 golden answer）
     * @param reply Agent 最终输出
     * @return 判定结果；当前实现不适用于该用例时返回 {@code null}
     */
    JudgeVerdict judge(EvalCase c, String reply);
}