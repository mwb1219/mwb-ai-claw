package com.mwb.ai.claw.example.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 配置：放行独立前端工程（example-web-frontend，Vite dev server / 静态托管）跨域访问
 * {@code /agent/**}、{@code /memory/**} 及接入方 {@code /user/**}、{@code /auth/**} 接口。
 * <p>
 * 允许来源通过环境变量 {@code WEB_CORS_ORIGINS} 配置（逗号分隔），默认 {@code http://localhost:5173}（Vite dev）。
 * 生产环境请显式配置为实际前端域名；API Key 鉴权走 Header，无需 Cookie，故不允许携带凭证。
 *
 * @author Frank Zhang
 */
@Configuration
@Profile("web")
public class CorsConfig implements WebMvcConfigurer {

    @Value("${WEB_CORS_ORIGINS:http://localhost:5173}")
    private String corsOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = corsOrigins.split(",");
        registry.addMapping("/agent/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "X-API-Key", "Authorization")
                .maxAge(3600);
        registry.addMapping("/memory/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("Content-Type", "X-API-Key", "Authorization")
                .maxAge(3600);
        registry.addMapping("/user/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "X-API-Key", "Authorization")
                .maxAge(3600);
        registry.addMapping("/auth/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-API-Key", "Authorization")
                .maxAge(3600);
    }
}
