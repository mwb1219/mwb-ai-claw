package com.mwb.ai.claw.infrastructure.config;

import lombok.Data;

import java.util.List;

/**
 * agents.json 顶层结构（Agent 注册表，与编排解耦，跨编排复用）。
 */
@Data
public class AgentRegistryFile {

    /** Agent 定义列表 */
    private List<AgentProperties.AgentConfig> agents;
}
