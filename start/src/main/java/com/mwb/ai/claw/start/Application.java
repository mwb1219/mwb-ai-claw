package com.mwb.ai.claw.start;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 服务端示例应用（mwb-ai-claw Spring Boot Starter 使用方）。
 * <p>
 * 本类仅扫描 {@code com.mwb.ai.claw.start} 包自身（示例配置类）；
 * 框架核心 Bean（记忆 / LLM / 工具 / 编排 / REST / WebSocket）由
 * {@code mwb-ai-claw-spring-boot-starter} 的 {@code ClawAutoConfiguration} 自动装配。
 * <p>
 * 默认 profile=web（REST/SSE/WebSocket 服务端形态）；{@code --spring.profiles.active=shell}
 * 切换为 CLI 形态（application-shell.yml 关闭内嵌 Web 容器）。
 *
 * @author Frank Zhang
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
