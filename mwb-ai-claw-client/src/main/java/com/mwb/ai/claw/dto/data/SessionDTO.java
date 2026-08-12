package com.mwb.ai.claw.dto.data;

import lombok.Data;

import java.util.List;

/**
 * 会话 DTO
 */
@Data
public class SessionDTO {

    private String sessionId;

    private String agentId;

    private String title;

    /** 状态：ACTIVE / CLOSED */
    private String status;

    /** 创建时间戳 */
    private long createTime;

    /** 最后更新时间戳 */
    private long updateTime;

    private List<MessageDTO> messages;
}
