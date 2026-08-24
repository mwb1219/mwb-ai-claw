package com.mwb.ai.claw.example.commerce.tenant;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.tenant.TenantGateway;

/**
 * 电商多租户网关（T2）：对接「商户注册表」，将 API Key 反解为 [店铺(tenantId), 客户经理(userId)]。
 * <p>
 * 演示真实租户体系接入框架的方式：
 * <ul>
 *   <li>框架鉴权链路（{@code AuthInterceptor}）优先调用本 Bean 反查身份，写入 {@code AgentScope}
 *       实现按店铺隔离的会话 / 记忆；未命中再回退静态 {@code agent.auth.api-keys}；</li>
 *   <li>本示例以内存注册表演示：{@code sk-store-a} → 店铺 store-a/操作员 op-a，{@code sk-store-b} → 店铺 store-b/操作员 op-b。
 *       真实项目应将 {@link #registerTenant} 替换为从租户表 / SSO / IAM 查询，并管理密钥生命周期（签发 / 轮换 / 吊销）；</li>
 *   <li>配合 {@code CommerceDataStore} 按 tenantId 隔离商品 / 订单 / 活动数据，演示「多租户数据隔离」。</li>
 * </ul>
 */
@Component
public class CommerceTenantGateway implements TenantGateway {

    /** API Key → [tenantId, userId]，作为演示用的商户注册表（容量小，演示足够） */
    private final Map<String, String[]> registry = new LinkedHashMap<>();

    public CommerceTenantGateway() {
        // 店铺 store-a：店长 op-a
        registerTenant("sk-store-a", "store-a", "op-a");
        // 店铺 store-b：店长 op-b（不同店铺，数据互相隔离）
        registerTenant("sk-store-b", "store-b", "op-b");
    }

    /**
     * 注册一个商户身份（示例：内存注册表；真实项目改为对接租户存储 / 外部 IAM）。
     */
    public void registerTenant(String apiKey, String tenantId, String userId) {
        registry.put(apiKey, new String[]{tenantId, userId});
    }

    @Override
    public String[] resolveApiKey(String apiKey) {
        if (apiKey == null) {
            return null;
        }
        return registry.get(apiKey.trim());
    }
}