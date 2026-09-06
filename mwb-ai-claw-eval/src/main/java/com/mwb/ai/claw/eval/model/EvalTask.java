package com.mwb.ai.claw.eval.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 评测数据集元信息：一组用例的公共配置与身份。
 * <p>
 * 由数据集文件 {@code task} 段解析；{@code defaultJudge} / {@code mode} / {@code model}
 * 可被单个 {@link EvalCase} 覆盖。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalTask {

    /** 数据集 id（报告标识，用于 diff 对齐） */
    private String id;

    /** 数据集显示名 */
    private String name;

    /** 数据集版本（改动数据集时递增，用于 golden/baseline 存档） */
    private String version = "1.0";

    /** 是否启用（false 跳过整组评估） */
    private boolean enabled = true;

    /** 默认判定方式（rule | llm），单个 case 可覆盖 */
    private JudgeType defaultJudge = JudgeType.RULE;

    /** 默认 LLM 裁判返回格式，单个 case 可覆盖 */
    private JudgeReplyMode mode = JudgeReplyMode.BOOLEAN;

    /** 默认裁判模型（未设置走全局 judge-model / .env 兜底） */
    private String model;
}