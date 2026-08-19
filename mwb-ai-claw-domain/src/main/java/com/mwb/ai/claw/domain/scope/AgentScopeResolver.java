package com.mwb.ai.claw.domain.scope;

/**
 * 请求 → AgentScope 解析 SPI（依赖倒置端口）。
 * <p>
 * 服务端实现：读 {@link AgentScopeContext}（Auth 拦截器 / WS 握手解析后设置）；
 * 客户端 / 默认实现：返回 {@link AgentScope#defaultScope()}。
 */
public interface AgentScopeResolver {

    /**
     * 解析当前请求的租户/用户维度。
     */
    AgentScope resolve();
}
