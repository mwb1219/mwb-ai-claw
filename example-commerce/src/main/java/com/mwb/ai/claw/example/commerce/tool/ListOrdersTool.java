package com.mwb.ai.claw.example.commerce.tool;

import java.util.List;

import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.example.commerce.store.CommerceDataStore;
import com.mwb.ai.claw.example.commerce.store.CommerceDataStore.Order;

/**
 * 订单查询工具（list_orders）：列出当前店铺的订单。
 */
@Component
public class ListOrdersTool extends AbstractCommerceTool {

    private static final String NAME = "list_orders";

    private static final String PARAMS_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"status\":{\"type\":\"string\",\"description\":\"按订单状态过滤（可选）：待发货/已发货/已完成\"}"
            + "}"
            + "}";

    public ListOrdersTool(CommerceDataStore store) {
        super(store);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        return new ToolSpec(NAME, "查询当前店铺的订单列表，包含订单号、商品、数量、金额与状态。",
                PARAMS_SCHEMA);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        return withCurrentStore(tenant -> {
            List<Order> orders = store.orders(tenant);
            if (orders.isEmpty()) {
                return ToolResult.success("当前店铺没有订单。");
            }
            StringBuilder sb = new StringBuilder("当前店铺[" + tenant + "]订单：\n");
            for (int i = 0; i < orders.size(); i++) {
                Order o = orders.get(i);
                sb.append(i + 1).append(". 订单 ").append(o.id).append("：sku=").append(o.productId)
                        .append("，x").append(o.quantity).append("，￥").append(o.amount)
                        .append("，状态[").append(o.status).append("]\n");
            }
            return ToolResult.success(sb.toString());
        });
    }
}