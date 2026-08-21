package com.mwb.ai.claw.domain.tenant;

/**
 * 租户鉴权 SPI：框架在鉴权时将 API Key 反解为 {@code [tenantId, userId]}，写入 {@code AgentScope}
 * 实现多租户 / 多用户维度的数据隔离。
 * <p>
 * 框架本身不存储租户信息、也不提供租户管理能力；接入方（如 example-web）按需提供本端口的实现
 * （文件 / 数据库 / 外部 IAM 等）并以 Bean 注入，即可接入框架的鉴权链路。未提供实现时，框架鉴权
 * 仅回退到静态 {@code agent.auth.api-keys} 配置。
 */
public interface TenantGateway {

    /**
     * 反查 API Key 对应的租户 / 用户身份。
     *
     * @param apiKey 请求携带的 API Key（已去除前后空白）
     * @return 长度 2 的数组 {@code [tenantId, userId]}；未命中返回 {@code null}
     */
    String[] resolveApiKey(String apiKey);
}
