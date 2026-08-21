package com.mwb.ai.claw.dto.data;

import lombok.Data;

import java.util.List;

/**
 * 消息 DTO
 */
@Data
public class MessageDTO {

    /** 角色：system / user / assistant / tool */
    private String role;

    /** 消息内容 */
    private String content;

    /** 时间戳（毫秒） */
    private long timestamp;

    /** assistant 消息携带的工具调用 */
    private List<ToolCallDTO> toolCalls;
}
