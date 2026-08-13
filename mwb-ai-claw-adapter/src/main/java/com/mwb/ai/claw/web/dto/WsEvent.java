package com.mwb.ai.claw.web.dto;

import lombok.Data;

/**
 * WebSocket 服务端推送事件体。
 */
@Data
public class WsEvent {

    /** 事件类型：session / step / token / tool_name / tool_args / reply / done / error */
    private String type;

    /** 事件数据（可为 null） */
    private String data;

    public WsEvent() {
    }

    public WsEvent(String type, String data) {
        this.type = type;
        this.data = data;
    }
}
