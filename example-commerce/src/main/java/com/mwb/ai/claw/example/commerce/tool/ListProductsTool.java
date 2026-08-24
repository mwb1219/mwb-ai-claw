package com.mwb.ai.claw.example.commerce.tool;

import java.util.List;

import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.example.commerce.store.CommerceDataStore;
import com.mwb.ai.claw.example.commerce.store.CommerceDataStore.Product;

/**
 * 商品查询工具（list_products）：列出当前店铺在售商品。
 * <p>
 * 演示多模态内容扩展：返回包含商品图片 URL 的 markdown（标题链接 + 图片），
 * Agent 可直接在回复中渲染商品图。
 */
@Component
public class ListProductsTool extends AbstractCommerceTool {

    private static final String NAME = "list_products";

    private static final String PARAMS_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"limit\":{\"type\":\"integer\",\"description\":\"返回条数上限（可选，默认全部）\"}"
            + "}"
            + "}";

    public ListProductsTool(CommerceDataStore store) {
        super(store);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        return new ToolSpec(NAME, "查询当前店铺的在售商品列表。返回商品名称、价格、卖点描述与商品图片 URL。",
                PARAMS_SCHEMA);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        return withCurrentStore(tenant -> {
            List<Product> products = store.products(tenant);
            if (products.isEmpty()) {
                return ToolResult.success("当前店铺没有在售商品。");
            }
            StringBuilder sb = new StringBuilder("当前店铺[" + tenant + "]在售商品：\n");
            for (int i = 0; i < products.size(); i++) {
                Product p = products.get(i);
                sb.append(i + 1).append(". **").append(p.name).append("**（sku=").append(p.id)
                        .append("，￥").append(p.price).append("）— ").append(p.description).append("\n");
                sb.append("   `").append(p.imageUrl).append("`").append("\n");
                sb.append("   ![](").append(p.imageUrl).append(")").append("\n");
            }
            return ToolResult.success(sb.toString());
        });
    }
}