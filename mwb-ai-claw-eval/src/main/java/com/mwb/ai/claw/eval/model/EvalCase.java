package com.mwb.ai.claw.eval.model;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 单条评测用例：一份输入 {@code prompt} + 期望 {@code expected}（golden answer / 判定依据）。
 * <p>
 * {@code judge} / {@code mode} / {@code rule} 为可选项，缺省继承数据集的 {@code EvalTask.defaultJudge}；
 * 仅 {@code prompt} 与 {@code expected} 必填（此时只能走 {@link JudgeType#LLM} 语义裁判）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalCase {

    /** 用例 id（数据集内唯一，用于报告对齐与 diff） */
    private String id;

    /** 用例名称（描述性，便于阅读报告） */
    private String name;

    /** 发给 Agent 的输入 */
    private String prompt;

    /** 期望的 golden answer / 判定依据 */
    private String expected;

    /** 判定方式，按用例覆盖数据集的 defaultJudge（rule | llm），缺省为空表示继承 */
    private JudgeType judge;

    /** LLM 裁判返回格式（quote | number | boolean），缺省见 EvalTask.mode */
    private JudgeReplyMode mode;

    /** 确定性规则（可选）：优先低成本判定，未命中再回退 LLM */
    private EvalRule rule;

    /** 标签元数据（用于 {tag,value} 过滤子集评估） */
    private Map<String, String> metadata = new HashMap<>();
}