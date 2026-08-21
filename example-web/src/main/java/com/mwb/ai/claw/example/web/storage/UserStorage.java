package com.mwb.ai.claw.example.web.storage;

import java.util.Optional;

import com.mwb.ai.claw.domain.tenant.TenantGateway;
import com.mwb.ai.claw.example.web.model.User;

/**
 * 用户存储端口（storage 层）：example-web 用户数据的持久化抽象。
 * <p>
 * 同时继承框架 SPI {@link TenantGateway}（{@code resolveApiKey}），将 API Key 反解为
 * 固定租户 + 用户名。
 */
public interface UserStorage extends TenantGateway {

    /** 按用户名查询，不存在返回 empty */
    Optional<User> findByUsername(String username);

    /** 新增或覆盖用户（按用户名判断），立即持久化 */
    User save(User user);
}
