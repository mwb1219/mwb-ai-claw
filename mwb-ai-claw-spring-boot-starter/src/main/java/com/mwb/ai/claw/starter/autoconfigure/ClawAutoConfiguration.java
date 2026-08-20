package com.mwb.ai.claw.starter.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * mwb-ai-claw 服务端自动装配入口。
 * <p>
 * 使用方仅需引入本 Starter 依赖，即自动扫描并装配：
 * <ul>
 *   <li>{@code infrastructure}：记忆 / LLM / 工具 / 编排 / 存储（文件 / JDBC）等默认实现；</li>
 *   <li>{@code agent}：应用层用例（AgentServiceImpl / ApprovalService / 命令执行器）；</li>
 *   <li>{@code web}：REST / SSE / WebSocket 多渠道适配器（{@code @Profile("web")}）；</li>
 *   <li>{@code shell}：CLI 适配器（{@code @Profile("shell")}）。</li>
 * </ul>
 * <p>
 * 核心端口实现（存储 / LLM / 网关等）为普通 POJO，由
 * {@code ClawCoreAutoConfiguration}（spring.factories 自动装配）以方法级
 * {@code @ConditionalOnMissingBean} 统一注册；使用方声明同名类型的 {@code @Bean} /
 * {@code @Component} 即可覆盖默认实现（如自定义 MemoryPageStore / LlmGateway）。
 * <p>
 * 存储类型通过 {@code agent.storage.type}（file | db）选择，会话锁固定本地 JVM 实现；
 * 默认文件存储 + 本地锁。
 *
 * @author Frank Zhang
 */
@AutoConfiguration
@ComponentScan(basePackages = {
        "com.mwb.ai.claw.infrastructure",
        "com.mwb.ai.claw.agent",
        "com.mwb.ai.claw.shell",
        "com.mwb.ai.claw.web"
})
public class ClawAutoConfiguration {
}
