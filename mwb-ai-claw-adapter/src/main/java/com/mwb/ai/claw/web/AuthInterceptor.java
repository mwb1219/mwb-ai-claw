package com.mwb.ai.claw.web;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.infrastructure.config.AuthProperties;

/**
 * API Key 认证拦截器（覆盖 /agent/** 同步接口与 SSE 流式接口）：
 * <ol>
 *   <li>认证关闭（auth.enabled=false）：直接放行，scope = default（行为与现状一致）；</li>
 *   <li>认证开启：依次校验 {@code X-API-Key} 头 / {@code Authorization: Bearer} / {@code ?apiKey=}（SSE，EventSource 无法自定义 Header）；</li>
 *   <li>校验通过：key 反查 (tenantId, userId) 写 {@link AgentScopeContext}，请求结束 finally 清理。</li>
 * </ol>
 */
@Component
@Profile("web")
public class AuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    @Resource
    private AuthProperties authProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!authProperties.isEnabled()) {
            AgentScopeContext.set(AgentScope.defaultScope());
            return true;
        }
        String apiKey = resolveApiKey(request);
        String[] resolved = apiKey != null ? authProperties.resolve(apiKey) : null;
        if (resolved == null) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"errCode\":\"B_AGENT_AUTH_FAILED\",\"errMessage\":\"认证失败: 无效的 API Key\"}");
            return false;
        }
        AgentScopeContext.set(AgentScope.of(resolved[0], resolved[1]));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AgentScopeContext.clear();
    }

    /**
     * 解析 API Key：Header（可配置，默认 X-API-Key）→ Authorization: Bearer → ?apiKey= 查询参数。
     */
    private String resolveApiKey(HttpServletRequest request) {
        String header = request.getHeader(authProperties.getHeader());
        if (header != null && !header.trim().isEmpty()) {
            return header.trim();
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            return auth.substring(BEARER_PREFIX.length()).trim();
        }
        String param = request.getParameter("apiKey");
        return param != null ? param.trim() : null;
    }
}
