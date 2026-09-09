package com.mwb.ai.claw.eval.dataset;

import com.mwb.ai.claw.eval.model.EvalDataset;

import lombok.Data;

/**
 * 自动生成评测数据集的结果：已落盘文件路径 + 数据集摘要 + 完整内容（供前端预览/回填）。
 * <p>
 * 与 {@link GenerateDatasetCommand} 配套，属于 {@code mwb-ai-claw-eval} 库能力。
 */
@Data
public class GenerateDatasetResult {

    /** 生成的 JSON 数据集文件绝对路径（可直接作为 /eval/run 的 datasetPath） */
    private String datasetPath;

    /** 数据集 id（task.id） */
    private String taskId;

    /** 数据集显示名（task.name） */
    private String taskName;

    /** 用例数 */
    private int caseCount;

    /** 完整数据集内容（任务元信息 + 用例），携带 prompt/expected/rule */
    private EvalDataset dataset;

    /** 生成所用模型 */
    private String model;
}
