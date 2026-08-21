package com.mwb.ai.claw.example.web.config;

import javax.annotation.Resource;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.mwb.ai.claw.web.AuthInterceptor;

/**
 * 接入方拦截配置：复用框架 {@link AuthInterceptor} 保护本接入方的用户管理接口 {@code /user/**}。
 * <p>
 * 框架只拦截自身提供的 {@code /agent/**}；接入方业务接口的鉴权范围由接入方自行声明。
 * {@code /auth/**}（注册 / 登录）保持公开，不在此拦截。
 */
@Configuration
@Profile("web")
public class WebSecurityConfig implements WebMvcConfigurer {

    @Resource
    private AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/user/**");
    }
}
