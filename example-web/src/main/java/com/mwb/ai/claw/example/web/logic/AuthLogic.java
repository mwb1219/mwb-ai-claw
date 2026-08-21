package com.mwb.ai.claw.example.web.logic;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.mwb.ai.claw.example.web.common.BizException;
import com.mwb.ai.claw.example.web.common.PasswordUtils;
import com.mwb.ai.claw.example.web.model.User;
import com.mwb.ai.claw.example.web.service.UserService;

/**
 * 认证业务逻辑（logic 层）：注册 / 登录用例编排。注册创建用户并签发 API Key，登录校验密码后返回用户。
 */
@Service
public class AuthLogic {

    private final UserService userService;

    public AuthLogic(UserService userService) {
        this.userService = userService;
    }

    /** 注册新用户：用户名唯一，密码哈希存储，签发 API Key */
    public User register(String username, String password, String name) {
        if (isBlank(username)) {
            throw new BizException("USERNAME_REQUIRED", "请输入用户名");
        }
        if (isBlank(password)) {
            throw new BizException("PASSWORD_REQUIRED", "请输入密码");
        }
        String uname = username.trim();
        if (userService.findByUsername(uname).isPresent()) {
            throw new BizException("USER_EXISTS", "用户名已存在");
        }
        User user = new User();
        user.setUsername(uname);
        user.setName(name == null ? "" : name.trim());
        user.setPasswordHash(PasswordUtils.hash(password));
        user.setApiKey(generateApiKey());
        return userService.create(user);
    }

    /** 登录：校验用户名密码，成功返回用户（含 API Key） */
    public User login(String username, String password) {
        User user = userService.findByUsername(username == null ? "" : username.trim())
                .orElseThrow(() -> new BizException("AUTH_FAILED", "用户名或密码错误"));
        if (password == null || !PasswordUtils.verify(password, user.getPasswordHash())) {
            throw new BizException("AUTH_FAILED", "用户名或密码错误");
        }
        return user;
    }

    private String generateApiKey() {
        return "sk-" + UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
