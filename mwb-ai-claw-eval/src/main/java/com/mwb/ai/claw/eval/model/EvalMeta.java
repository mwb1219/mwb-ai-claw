package com.mwb.ai.claw.eval.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 一次评测的运行元信息（环境与执行参数快照）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalMeta {

    /** 主导 Agent id */
    private String agentId;

    /** 执行所用模型 */
    private String model;

    /** LLM 裁判所用模型 */
    private String judgeModel;

    /** 判定策略（rule | llm | both，both 为 rule 先过、llm 兜底） */
    private String judge;

    /** 数据集版本 */
    private String datasetVersion;

    /** 环境指纹（配置/代码 hash，用于辨识报告归属） */
    private String envHash;
}