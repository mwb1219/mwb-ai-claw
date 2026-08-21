package com.mwb.ai.claw.example.web.dto;

import lombok.Data;

/**
 * 注册 / 登录请求体。
 */
@Data
public class AuthRequest {

    /** 登录名（唯一） */
    private String username;

    /** 密码（明文，仅用于注册 / 登录传输） */
    private String password;

    /** 显示名（仅注册时可选） */
    private String name;
}
