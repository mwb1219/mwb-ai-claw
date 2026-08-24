package com.mwb.ai.claw.web;

import javax.annotation.Resource;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册 {@link AuthInterceptor}，覆盖 /agent/**（含 SSE 流式）、/memory/**、
 * /rag/** 接口 —— 在请求前由 API Key 解析当前 (tenantId, userId) 写入 {@link AgentScopeContext}，
 * 使记忆页 / 知识库页面按当前身份访问对应 scope 的数据。
 */
@Configuration
@Profile("web")
public class WebMvcConfig implements WebMvcConfigurer {

    @Resource
    private AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/agent/**", "/memory/**", "/rag/**");
    }
}
