package com.mwb.ai.claw.example.commerce.tool;

import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.example.commerce.store.CommerceDataStore;
import com.mwb.ai.claw.example.commerce.store.CommerceDataStore.Campaign;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.springframework.stereotype.Component;

/**
 * 创建营销活动工具（create_campaign）：为当前店铺创建一个营销活动。
 * <p>
 * 演示「高权限写操作」的人工审批在环：工具本身只负责执行；生产中应配合
 * delegate 编排的 {@code approvalGate} / 工具级权限，或在本工具内接入
 * {@code ApprovalService}，使人工作出创建/投放决策。
 */
@Component
public class CreateCampaignTool extends AbstractCommerceTool {

    private static final String NAME = "create_campaign";

    private static final String PARAMS_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"name\":{\"type\":\"string\",\"description\":\"活动名称\"},"
            + "\"description\":{\"type\":\"string\",\"description\":\"优惠规则描述\"},"
            + "\"status\":{\"type\":\"string\",\"description\":\"初始状态（可选，默认未开始）\"}"
            + "},"
            + "\"required\":[\"name\",\"description\"]"
            + "}";

    public CreateCampaignTool(CommerceDataStore store) {
        super(store);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public ToolSpec getSpec() {
        return new ToolSpec(NAME, "为当前店铺创建一个营销活动。注意：这是高权限写操作，创建前应取得用户确认。",
                PARAMS_SCHEMA);
    }

    @Override
    public ToolResult execute(String argumentsJson) {
        return withCurrentStore(tenant -> {
            Params params = JsonUtils.fromJson(argumentsJson == null ? "{}" : argumentsJson, Params.class);
            if (params == null || params.getName() == null || params.getName().trim().isEmpty()) {
                return ToolResult.error("参数缺失: name 不能为空");
            }
            if (params.getDescription() == null || params.getDescription().trim().isEmpty()) {
                return ToolResult.error("参数缺失: description 不能为空");
            }
            String status = (params.getStatus() == null || params.getStatus().trim().isEmpty())
                    ? "未开始" : params.getStatus().trim();
            Campaign c = store.createCampaign(tenant, params.getName().trim(),
                    params.getDescription().trim(), status);
            return ToolResult.success("已创建营销活动：" + c.name + "（id=" + c.id + "，状态[" + c.status + "]）");
        });
    }

    /** 工具入参 */
    public static class Params {
        private String name;
        private String description;
        private String status;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}