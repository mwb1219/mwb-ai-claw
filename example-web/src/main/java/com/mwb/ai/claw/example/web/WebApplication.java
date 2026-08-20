package com.mwb.ai.claw.example.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Web 服务端示例应用（mwb-ai-claw Spring Boot Starter 使用方）。
 * <p>
 * 本类仅扫描 {@code com.mwb.ai.claw.example.web} 包自身（示例配置类）；
 * 框架核心 Bean（记忆 / LLM / 工具 / 编排 / 存储）与多渠道适配器（REST / SSE /
 * WebSocket）由 {@code mwb-ai-claw-spring-boot-starter} 的
 * {@code ClawAutoConfiguration} 自动装配。
 * <p>
 * 默认 profile=web（内嵌 Tomcat，暴露 REST / SSE / WebSocket 接口，端口默认 8080）；
 * 完整配置见 src/main/resources/application.yml，密钥填入根目录 .env（复制 .env.example）。
 *
 * @author Frank Zhang
 */
@SpringBootApplication
public class WebApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
