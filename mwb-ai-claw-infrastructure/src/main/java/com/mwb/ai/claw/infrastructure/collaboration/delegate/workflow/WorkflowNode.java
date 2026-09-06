package com.mwb.ai.claw.infrastructure.collaboration.delegate.workflow;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 工作流节点：预定义静态图中的一个节点（H1-P2）。
 * <p>
 * 与 delegate 的规划协议不同，工作流图是确定性的（预先定义拓扑、依赖与条件），
 * 由 {@link WorkflowOrchestrator} 映射为 {@link com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDefinition}
 * 交给同一可恢复推进机执行。节点类型：
 * <ul>
 *     <li>{@code llm}：纯 LLM 对话（走叶子 directExecute，需要 {@code agentId}）；</li>
 *     <li>{@code tool}：工具型 Agent（agent 自带 ReAct 工具，同样走叶子，需要 {@code agentId}）；</li>
 *     <li>{@code human}：人工门禁（执行时挂起为 SUSPENDED + {@code pendingKind=human_input}，resume 承载人工答复）；</li>
 *     <li>{@code route}：`condition` + 已有节点结果 → LLM 判官选分支（需 {@code condition}）；</li>
 *     <li>{@code nest}：委托子编排（需要 {@code orchestrationId}，复用父等子 run）。</li>
 * </ul>
 */
@Data
public class WorkflowNode {

    /** 节点 id（图内唯一） */
    private String id;

    /** 节点类型：llm | tool | human | route | nest */
    private String type;

    /** 标题 */
    private String title;

    /** 描述 / 任务说明（供 promz 使用） */
    private String description;

    /** 执行 Agent id（llm/tool 必需；引用 agents.json，未知 id 校验期报错） */
    private String agentId;

    /** 嵌套编排 id（nest 必需） */
    private String orchestrationId;

    /** 路由条件（route 必需，描述性引导条件；由 LLM 判官结合已产生结果裁决） */
    private String condition;

    /** 人工门禁开关（true 时该节点为人工确认/输入节点；失败-校验在 parser 中处理） */
    private boolean gate;

    /** 依赖节点 id 列表（可空；依赖先执行，结果注入本节点 prompt） */
    private List<String> dependsOn = new ArrayList<>();
}