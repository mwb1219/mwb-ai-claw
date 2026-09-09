package com.mwb.ai.claw.eval.dataset;

import lombok.Data;

/**
 * 自动生成评测数据集的请求体：按主题用 LLM 产出 {prompt, expected, rule} 用例并落盘为数据集文件。
 * <p>
 * 属于 {@code mwb-ai-claw-eval} 库能力，供 REST / shell 等任意接入方复用（如 example-web 的
 * {@code POST /eval/dataset/generate}）。
 */
@Data
public class GenerateDatasetCommand {

    /** 主题 / 领域（必填）：生成用例围绕的主题，如「高可用架构」或「Spring Boot 事务」 */
    private String topic;

    /** 用例类型提示（可选，默认 qa）：qa | math | reasoning | code 等，注入生成 prompt 引导风格 */
    private String type;

    /** 生成用例数（默认 5，上限 20） */
    private int count = 5;

    /** 用于生成的主 Agent id（可选：缺省 default；取该 Agent 的模型配置调用 LLM） */
    private String agentId;

    /** 生成所用模型（可选：覆盖 Agent 上的模型配置） */
    private String model;

    /** 数据集 id（可选：缺省按 topic 生成 slug；需唯一） */
    private String taskId;

    /** 数据集显示名（可选：缺省取 topic） */
    private String name;
}
