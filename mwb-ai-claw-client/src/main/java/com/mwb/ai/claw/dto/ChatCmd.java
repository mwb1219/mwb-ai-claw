package com.mwb.ai.claw.dto;

import lombok.Data;

/**
 * Agent 对话命令
 */
@Data
public class ChatCmd {

    /** 会话 ID，为空则自动创建新会话 */
    private String sessionId;

    /** Agent 标识，为空则使用默认 Agent */
    private String agentId;

    /** 编排 id（显式指定协作编排，优先于意图选择；为空则按意图自动选择，未命中回退默认编排） */
    private String orchestrationId;

    /** 用户输入消息 */
    private String message;
}
