package com.mwb.ai.claw.example.commerce.tool;

import java.util.function.Function;

import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.example.commerce.store.CommerceDataStore;

/**
 * 电商业务工具基类：统一封装「当前店铺（tenantId）」解析与租户隔离校验。
 * <p>
 * 演示点：
 * <ul>
 *   <li>工具实现 {@link ToolExecutor} 并注册为 Spring Bean，框架 {@link ToolGatewayImpl}
 *       会自动收集，无需改动主链路（零代码接入工具）；</li>
 *   <li>数据读取基于 {@link AgentScopeContext} 中的 tenantId，实现多店铺数据隔离（联动 T2
 *       {@code CommerceTenantGateway}）。</li>
 * </ul>
 */
public abstract class AbstractCommerceTool implements ToolExecutor {

    protected final CommerceDataStore store;

    protected AbstractCommerceTool(CommerceDataStore store) {
        this.store = store;
    }

    /**
     * 以「当前店铺」为单位执行业务操作：解析 tenantId 并校验店铺存在，再委托给 action。
     * 未识别店铺（未带店铺 API Key）或店铺不存在时返回明确的业务错误，不抛异常。
     */
    protected ToolResult withCurrentStore(Function<String, ToolResult> action) {
        String tenant = AgentScopeContext.get().getTenantId();
        if (tenant == null || tenant.trim().isEmpty()) {
            return ToolResult.error("未识别店铺（tenantId 为空），请先以店铺 API Key 登录（如 X-API-Key: <store-a-key>）");
        }
        if (!store.hasStore(tenant)) {
            return ToolResult.error("店铺不存在或无权访问: " + tenant);
        }
        return action.apply(tenant);
    }
}