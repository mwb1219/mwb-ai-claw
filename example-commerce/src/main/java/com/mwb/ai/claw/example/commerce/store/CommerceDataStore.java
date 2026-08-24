package com.mwb.ai.claw.example.commerce.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * 电商业务数据源（内存模拟）：按「店铺（tenantId）」维度维护商品 / 订单 / 营销活动。
 * <p>
 * 仅用于示例，真实业务应由业务侧 API / DB 提供。核心演示点：
 * <ul>
 *   <li>租户隔离：各店铺数据互不可见，工具读取时从 {@link com.mwb.ai.claw.domain.scope.AgentScopeContext}
 *       取当前 tenantId，体现「多店铺 / 多租户」隔离语义（T2）；</li>
 *   <li>多模态：商品返回 {@code imageUrl}（图片 URL）字段，供 Agent 在回复中以 markdown 图片输出，演示内容层扩展。</li>
 * </ul>
 */
@Component
public class CommerceDataStore {

    /** 店铺（tenantId）→ 商品；各店铺内置一批 mock 商品（含图片 URL） */
    private final Map<String, List<Product>> products = new LinkedHashMap<>();

    /** 店铺（tenantId）→ 订单 */
    private final Map<String, List<Order>> orders = new LinkedHashMap<>();

    /** 店铺（tenantId）→ 营销活动 */
    private final Map<String, List<Campaign>> campaigns = new LinkedHashMap<>();

    private final AtomicInteger campaignSeq = new AtomicInteger(100);

    public CommerceDataStore() {
        // 店铺 store-a
        products.put("store-a", List.of(
                new Product("p-a1", "无线蓝牙耳机 Pro", 299.0, "降噪 · 30小时续航", "https://images.example.com/store-a/p-a1.png"),
                new Product("p-a2", "商务双肩包", 199.0, "15.6寸电脑仓 · 防泼水", "https://images.example.com/store-a/p-a2.png"),
                new Product("p-a3", "智能手环", 149.0, "心率监测 · 睡眠分析", "https://images.example.com/store-a/p-a3.png")
        ));
        orders.put("store-a", List.of(
                new Order("o-a11", "p-a1", 2, 598.0, "已发货"),
                new Order("o-a12", "p-a3", 1, 149.0, "待发货")
        ));
        campaigns.put("store-a", List.of(
                new Campaign("c-a1", "耳机周年庆", "满299减30", "进行中"),
                new Campaign("c-a2", "开学季", "全店9折", "未开始")
        ));

        // 店铺 store-b（不同数据，体现隔离）
        products.put("store-b", List.of(
                new Product("p-b1", "保温杯 500ml", 89.0, "316不锈钢 · 24小时保温", "https://images.example.com/store-b/p-b1.png"),
                new Product("p-b2", "复古台灯", 129.0, "三档调光 · 暖光阅读", "https://images.example.com/store-b/p-b2.png")
        ));
        orders.put("store-b", List.of(
                new Order("o-b21", "p-b1", 3, 267.0, "已完成")
        ));
        campaigns.put("store-b", List.of(
                new Campaign("c-b1", "春季上新", "任意两件95折", "进行中")
        ));
    }

    /** 店铺是否存在（用于租户隔离校验） */
    public boolean hasStore(String tenantId) {
        return tenantId != null && products.containsKey(tenantId);
    }

    public List<Product> products(String tenantId) {
        return clone(products.get(tenantId));
    }

    public List<Order> orders(String tenantId) {
        return clone(orders.get(tenantId));
    }

    public List<Campaign> campaigns(String tenantId) {
        return clone(campaigns.get(tenantId));
    }

    /** 创建营销活动并登记到该店铺 */
    public Campaign createCampaign(String tenantId, String name, String description, String status) {
        List<Campaign> list = campaigns.computeIfAbsent(tenantId, k -> new ArrayList<>());
        Campaign c = new Campaign("c-" + campaignSeq.incrementAndGet(), name, description, status);
        list.add(c);
        return c;
    }

    private <T> List<T> clone(List<T> src) {
        return src == null ? new ArrayList<>() : new ArrayList<>(src);
    }

    /** 商品 */
    public static class Product {
        public final String id;
        public final String name;
        public final double price;
        public final String description;
        public final String imageUrl;

        Product(String id, String name, double price, String description, String imageUrl) {
            this.id = id;
            this.name = name;
            this.price = price;
            this.description = description;
            this.imageUrl = imageUrl;
        }
    }

    /** 订单 */
    public static class Order {
        public final String id;
        public final String productId;
        public final int quantity;
        public final double amount;
        public final String status;

        Order(String id, String productId, int quantity, double amount, String status) {
            this.id = id;
            this.productId = productId;
            this.quantity = quantity;
            this.amount = amount;
            this.status = status;
        }
    }

    /** 营销活动 */
    public static class Campaign {
        public final String id;
        public final String name;
        public final String description;
        public final String status;

        Campaign(String id, String name, String description, String status) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.status = status;
        }
    }
}