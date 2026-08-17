package com.mwb.ai.claw.infrastructure.collaboration;

import lombok.Data;

/**
 * 流水线阶段定义（orchestrations.json 中 pipeline.config.stages 的单个元素）。
 * <p>
 * 由 PipelineOrchestrator 从编排定义的宽松 config Map 解析为类型化对象；
 * 编排注册中心与定义模型不感知具体编排结构，各插件自行解释自己的 config。
 */
@Data
public class PipelineStage {

    /** 阶段标识（trace / 产物文件名使用） */
    private String stageId;

    /** 执行该阶段的 Agent id（引用 agents.json 注册表） */
    private String agentId;

    /** 阶段提示词模板，支持 {input} / {artifacts} 占位符 */
    private String promptTemplate;

    /** 产物传递方式：text（默认，直接作为下一阶段输入）| file（落盘传文件路径） */
    private String pass;

    /** 失败策略：abort（默认，终止流水线）| continue（跳过本阶段继续） */
    private String onFailure;

    /** 思考模式开关（null=不覆盖 Agent 默认配置） */
    private Boolean thinking;

    /** 产物是否以文件方式传递（未配置 pass 时按 text 处理） */
    public boolean isFilePass() {
        return "file".equals(pass);
    }

    /** 阶段失败是否终止流水线（未配置 onFailure 时按 abort 处理） */
    public boolean abortOnFailure() {
        return !"continue".equals(onFailure);
    }
}
