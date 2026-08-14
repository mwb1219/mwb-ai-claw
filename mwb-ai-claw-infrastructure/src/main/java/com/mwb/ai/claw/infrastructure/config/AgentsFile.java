package com.mwb.ai.claw.infrastructure.config;

import lombok.Data;

import java.util.List;

/**
 * {mode}-agents.json 顶层结构。
 * <p>
 * 例如 routing-agents.json 对应专家路由模式，orchestration-agents.json 对应编排模式。
 */
@Data
public class AgentsFile {

    /** 协作模式 */
    private String mode;

    /** Agent 定义列表 */
    private List<AgentProperties.AgentConfig> agents;
}
