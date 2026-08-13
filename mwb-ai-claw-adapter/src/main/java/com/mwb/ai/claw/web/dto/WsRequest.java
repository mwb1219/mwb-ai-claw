package com.mwb.ai.claw.web.dto;

import lombok.Data;

/**
 * WebSocket 客户端请求体。
 */
@Data
public class WsRequest {

    /** 消息类型：chat */
    private String type;

    /** 用户消息内容 */
    private String message;

    /** 会话 ID（可选，缺省自动创建） */
    private String sessionId;

    /** Agent ID（可选） */
    private String agentId;
}
