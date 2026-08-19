package com.mwb.ai.claw.web;

import javax.annotation.Resource;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 {@link AuthInterceptor}，覆盖 /agent/** 全部接口（含 SSE 流式）。
 */
@Configuration
@Profile("web")
public class WebMvcConfig implements WebMvcConfigurer {

    @Resource
    private AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/agent/**");
    }
}
