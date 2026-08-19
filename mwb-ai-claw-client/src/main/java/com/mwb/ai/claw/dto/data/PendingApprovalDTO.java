package com.mwb.ai.claw.dto.data;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 待审批节点 DTO（P1 交互与上下文）：审批人可查看的任务与计划摘要。
 */
@Data
public class PendingApprovalDTO {

    /** 会话 id */
    private String sessionId;

    /** 层级标识：根层 "root"，子层为 todoId 路径（如 "t1/t1-1"） */
    private String layerKey;

    /** 该层任务描述（规划 Agent 收到的原始任务） */
    private String task;

    /** 计划 Todo 标题列表（摘要展示） */
    private List<String> todoTitles = new ArrayList<>();

    /** 计划 Todo 数量 */
    private int todoCount;

    /** 注册时间戳（毫秒） */
    private long createdAt;
}
