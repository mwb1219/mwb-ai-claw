package com.mwb.ai.claw.eval.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 一次评测的完整报告：摘要 + 各用例结果 + 元信息。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalReport {

    /** 数据集 id */
    private String taskId;

    /** 数据集名 */
    private String taskName;

    /** 评测时间戳（epoch 毫秒） */
    private long runAt;

    /** 运行元信息（环境/模型/判定策略） */
    private EvalMeta meta;

    /** 聚合摘要（通过率/延迟/token/成本） */
    private EvalSummary summary;

    /** 各用例结果（按定义顺序） */
    private List<CaseResult> cases = new ArrayList<>();
}