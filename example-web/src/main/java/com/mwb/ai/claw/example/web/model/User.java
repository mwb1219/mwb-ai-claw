package com.mwb.ai.claw.example.web.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

/**
 * 用户（example-web 接入方业务模型）：用户名唯一，同时作为框架 {@code AgentScope.userId}。
 * <p>
 * 密码哈希仅内部使用，不参与 JSON 序列化；apiKey 为登录凭证（注册 / 登录时签发）。
 */
@Data
public class User {

    /** 唯一登录名（同时作为 userId） */
    private String username;

    /** 显示名 */
    private String name;

    /** API Key（登录凭证，注册 / 登录时签发） */
    private String apiKey;

    /** 可用工具名列表（空 = 全部允许） */
    private List<String> tools = new ArrayList<>();

    /** 创建时间戳（毫秒） */
    private long createdAt;

    /** 密码哈希（salt:hash），仅内部使用 */
    @JsonIgnore
    private String passwordHash;
}
