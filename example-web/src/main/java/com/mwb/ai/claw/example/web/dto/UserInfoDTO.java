package com.mwb.ai.claw.example.web.dto;

import lombok.Data;

/**
 * 当前请求的用户身份信息（用于前端展示「当前身份」与后端是否强制鉴权）。
 */
@Data
public class UserInfoDTO {

    /** 用户名（同时作为 userId） */
    private String username;

    /** 显示名 */
    private String name;

    /** 租户 id（example-web 固定单租户） */
    private String tenantId;

    /** 后端是否开启鉴权 */
    private boolean authEnabled;
}
