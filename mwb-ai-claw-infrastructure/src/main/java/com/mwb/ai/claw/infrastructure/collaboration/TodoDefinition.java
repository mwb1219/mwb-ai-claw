package com.mwb.ai.claw.infrastructure.collaboration;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 委托编排的规划产物：主 Agent（规划者）输出的一项待办。
 * <p>
 * 由 TodoDelegateOrchestrator 从规划 Agent 输出的结构化 JSON 解析为类型化对象；
 * 编排注册中心与定义模型不感知具体编排结构，各插件自行解释自己的规划协议。
 */
@Data
public class TodoDefinition {

    /** Todo id（节点内唯一，如 t1 / t1-1） */
    private String todoId;

    /** 标题 */
    private String title;

    /** 任务描述（含完成标准，由规划 Agent 编写） */
    private String description;

    /** 执行 Agent id（引用 agents.json；未知 id 回退默认 Agent） */
    private String agentId;

    /** 依赖的 todoId 列表（可空；依赖先执行，结果注入该 Todo 的 prompt） */
    private List<String> dependsOn = new ArrayList<>();

    /** 生命周期状态（P1：审批门禁下 paused→approved→running→done；失败→failed；未启用门禁时执行中直接置 running/done） */
    private TodoStatus status;

    /** 嵌套编排 id（P2：可空；配置后该 Todo 委托给指定编排执行——conversational / delegate 自身，结果回传参与本层汇总） */
    private String orchestrationId;
}
