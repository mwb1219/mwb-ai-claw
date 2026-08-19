package com.mwb.ai.claw.web;

import java.util.Map;

import javax.annotation.Resource;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.config.AuthProperties;

/**
 * WebSocket 握手认证拦截器：握手阶段校验 {@code ?apiKey=} 查询参数或请求头，
 * 校验通过将 scope 写入 WS session attributes；业务线程（{@link AgentWebSocketHandler}）从 attributes 解析写 AgentScopeContext。
 * <p>
 * 覆盖 chat / approve / reject / pending_tasks 四类消息（均在 handleTextMessage 入口统一设置 scope）。
 */
@Component
@Profile("web")
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {

    /** WS session attributes 中存放 scope 的 key */
    public static final String SCOPE_ATTR = "agentScope";

    private static final String BEARER_PREFIX = "Bearer ";

    @Resource
    private AuthProperties authProperties;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!authProperties.isEnabled()) {
            attributes.put(SCOPE_ATTR, AgentScope.defaultScope());
            return true;
        }
        String apiKey = resolveApiKey(request);
        String[] resolved = apiKey != null ? authProperties.resolve(apiKey) : null;
        if (resolved == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(SCOPE_ATTR, AgentScope.of(resolved[0], resolved[1]));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private String resolveApiKey(ServerHttpRequest request) {
        // 查询参数 ?apiKey=（前端 WS 无法自定义 Header，一期支持）
        String apiKey = request.getURI().getQuery();
        if (apiKey != null) {
            String[] pairs = apiKey.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf('=');
                if (idx > 0 && "apiKey".equals(pair.substring(0, idx))) {
                    return pair.substring(idx + 1).trim();
                }
            }
        }
        // 请求头
        String header = request.getHeaders().getFirst(authProperties.getHeader());
        if (header != null && !header.trim().isEmpty()) {
            return header.trim();
        }
        String auth = request.getHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith(BEARER_PREFIX)) {
            return auth.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
