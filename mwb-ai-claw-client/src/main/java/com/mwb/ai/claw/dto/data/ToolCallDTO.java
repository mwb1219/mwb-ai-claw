package com.mwb.ai.claw.dto.data;

import lombok.Data;

/**
 * 工具调用 DTO（assistant 消息携带，供前端展示工具调用记录）
 */
@Data
public class ToolCallDTO {

    /** 工具调用 ID */
    private String id;

    /** 工具名称 */
    private String name;

    /** 入参 JSON 字符串 */
    private String arguments;
}
