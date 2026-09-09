package com.mwb.ai.claw.infrastructure.tool.builtin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 异步子代理引用参数（H3 · Agent-as-Tool，切片 2）：
 * {@code subagent_status} / {@code subagent_cancel} 的入参，仅需按 {@code agent_id} 定位一个已 spawn 的子代理。
 */
@Data
public class SubagentRefParams {

    @JsonProperty("agent_id")
    private String agentId;
}
