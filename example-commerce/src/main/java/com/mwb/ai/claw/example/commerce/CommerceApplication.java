package com.mwb.ai.claw.example.commerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 电商/营销运营助手示例应用（mwb-ai-claw 多扩展点集中演示）。
 * <p>
 * 本类负责：
 * <ul>
 *   <li>仅扫描 {@code com.mwb.ai.claw.example.commerce} 包自身（示例扩展组件）；</li>
 *   <li>框架核心 Bean 与多渠道适配器（REST / SSE / WebSocket）由
 *       {@code mwb-ai-claw-spring-boot-starter} 的自动装配提供。</li>
 * </ul>
 * <p>
 * 覆盖的扩展点（详见各组件类注释与 module 内 README）：
 * <ul>
 *   <li>自定义业务工具：{@code tool/}（商品 / 订单 / 营销活动），演示「新增 ToolExecutor Bean 自动收集」；</li>
 *   <li>RAG 业务知识库扩展：{@code rag/}（切分器包装 + 重排），演示「包装默认实现 / 增强」；</li>
 *   <li>自定义编排：{@code orchestration/MarketingOrchestrator}（type=marketing），演示「注册编排 type 插件」；</li>
 *   <li>多租户隔离：{@code tenant/CommerceTenantGateway}（多店铺 / 客户经理），演示「对接 TenantGateway」；</li>
 *   <li>审批门禁：编排与 delegate 配置均可开启（application.yml / orchestrations.json）。</li>
 * </ul>
 *
 * @author mwb1219
 */
@SpringBootApplication
public class CommerceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CommerceApplication.class, args);
    }
}