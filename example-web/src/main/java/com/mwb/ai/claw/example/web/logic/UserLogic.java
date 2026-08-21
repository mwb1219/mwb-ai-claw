package com.mwb.ai.claw.example.web.logic;

import org.springframework.stereotype.Service;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.example.web.dto.UserInfoDTO;
import com.mwb.ai.claw.example.web.model.User;
import com.mwb.ai.claw.example.web.service.UserService;
import com.mwb.ai.claw.infrastructure.config.AuthProperties;

/**
 * 用户管理业务逻辑（logic 层）：当前身份用例编排。
 */
@Service
public class UserLogic {

    private final UserService userService;
    private final AuthProperties authProperties;

    public UserLogic(UserService userService, AuthProperties authProperties) {
        this.userService = userService;
        this.authProperties = authProperties;
    }

    /** 查询当前请求身份（username/name/tenantId）及后端是否开启鉴权 */
    public UserInfoDTO current() {
        AgentScope scope = AgentScopeContext.get();
        String username = scope.getUserId();
        User user = username != null ? userService.findByUsername(username).orElse(null) : null;
        UserInfoDTO dto = new UserInfoDTO();
        dto.setUsername(username);
        dto.setName(user != null ? user.getName() : "");
        dto.setTenantId(scope.getTenantId());
        dto.setAuthEnabled(authProperties.isEnabled());
        return dto;
    }
}
