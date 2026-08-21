package com.mwb.ai.claw.example.web.controller;

import javax.annotation.Resource;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.example.web.dto.UserInfoDTO;
import com.mwb.ai.claw.example.web.logic.UserLogic;

/**
 * 用户管理 REST 接口（example-web 接入方业务）：当前身份查询。
 * <p>
 * 受框架 {@code AuthInterceptor} 保护（路径 /user/** 由接入方拦截配置放行），需携带有效 API Key。
 * 用户新增统一走 {@code /auth/register}，不再提供用户列表 / 更新 / 删除接口。
 */
@RestController
@RequestMapping("/user")
@Profile("web")
public class UserController {

    @Resource
    private UserLogic userLogic;

    /** 查询当前请求身份（username/name/tenantId）及后端是否开启鉴权 */
    @GetMapping("/current")
    public SingleResponse<UserInfoDTO> current() {
        return SingleResponse.of(userLogic.current());
    }
}
