package com.mwb.ai.claw.example.web.controller;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.example.web.common.BizException;
import com.mwb.ai.claw.example.web.dto.AuthRequest;
import com.mwb.ai.claw.example.web.logic.AuthLogic;
import com.mwb.ai.claw.example.web.model.User;

/**
 * 用户注册 / 登录接口（公开，不鉴权）。
 * <p>
 * 注册创建用户并签发 API Key，登录校验密码后返回用户（含 API Key）；前端拿到 API Key 后
 * 以 {@code X-API-Key} 访问受保护接口（/agent/**、/user/**）。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    @Resource
    private AuthLogic authLogic;

    /** 注册新用户，返回用户（含 API Key） */
    @PostMapping("/register")
    public SingleResponse<User> register(@RequestBody AuthRequest req) {
        try {
            return SingleResponse.of(authLogic.register(req.getUsername(), req.getPassword(), req.getName()));
        } catch (BizException e) {
            return SingleResponse.buildFailure(e.getErrCode(), e.getMessage());
        }
    }

    /** 登录，返回用户（含 API Key） */
    @PostMapping("/login")
    public SingleResponse<User> login(@RequestBody AuthRequest req) {
        try {
            return SingleResponse.of(authLogic.login(req.getUsername(), req.getPassword()));
        } catch (BizException e) {
            return SingleResponse.buildFailure(e.getErrCode(), e.getMessage());
        }
    }
}
