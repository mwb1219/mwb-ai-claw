package com.mwb.ai.claw.eval.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 一个评测数据集：元信息 {@link EvalTask} + 若干用例 {@link EvalCase}。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalDataset {

    /** 数据集元信息与公共判定配置 */
    private EvalTask task;

    /** 用例列表 */
    private List<EvalCase> cases = new ArrayList<>();
}