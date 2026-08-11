package com.mwb.ai.claw.dto;

import lombok.Data;

/**
 * 创建会话命令
 */
@Data
public class CreateSessionCmd {

    /** Agent 标识，为空则使用默认 Agent */
    private String agentId;

    /** 会话标题 */
    private String title;
}
