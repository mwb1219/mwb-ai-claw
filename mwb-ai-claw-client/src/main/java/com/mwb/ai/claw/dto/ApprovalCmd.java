package com.mwb.ai.claw.dto;

import lombok.Data;

/**
 * 人工审批命令（P1 交互与上下文）：定位待审批节点并给出决策。
 */
@Data
public class ApprovalCmd {

    /** 会话 id */
    private String sessionId;

    /** 层级标识：根层 "root"，子层为 todoId 路径（如 "t1/t1-1"） */
    private String layerKey;

    /** 审批决策：approve | reject（仅 REST 使用；WebSocket 由 type 区分） */
    private String action;
}
