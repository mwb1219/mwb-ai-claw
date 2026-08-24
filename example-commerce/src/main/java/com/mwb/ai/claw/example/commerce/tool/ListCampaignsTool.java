package com.mwb.ai.claw.example.commerce.tool;

import java.util.List;

import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.example.commerce.store.CommerceDataStore;
import com.mwb.ai.claw.example.commerce.store.CommerceDataStore.Campaign;

/**
 * 营销活动查询工具（list_campaigns）：列出当前店铺的营销活动。
 */
@Component
public class ListCampaignsTool extends AbstractCommerceTool {

    private static final String NAME = "list_campaigns";

    private static final String PARAMS_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"status\":{\"type\":\"string\",\"description\":\"按活动状态过滤（可选）：进行中/未开始\"}"
            + "}"
            + "}";

    public ListCampaignsTool(CommerceDataStore store) {
        super(store);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        return new ToolSpec(NAME, "查询当前店铺的营销活动列表，包含活动名称与优惠规则。",
                PARAMS_SCHEMA);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        return withCurrentStore(tenant -> {
            List<Campaign> campaigns = store.campaigns(tenant);
            if (campaigns.isEmpty()) {
                return ToolResult.success("当前店铺没有营销活动。");
            }
            StringBuilder sb = new StringBuilder("当前店铺[" + tenant + "]营销活动：\n");
            for (int i = 0; i < campaigns.size(); i++) {
                Campaign c = campaigns.get(i);
                sb.append(i + 1).append(". ").append(c.name).append("（id=").append(c.id)
                        .append("）：").append(c.description).append("，状态[").append(c.status).append("]\n");
            }
            return ToolResult.success(sb.toString());
        });
    }
}