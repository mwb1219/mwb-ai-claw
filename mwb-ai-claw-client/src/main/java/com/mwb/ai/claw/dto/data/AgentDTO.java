package com.mwb.ai.claw.dto.data;

import lombok.Data;

import java.util.List;

/**
 * Agent 配置 DTO（已废弃：全项目无任何引用，Agent 配置由 {@code AgentRegistryLoader} 直接读取 agents.json，不经过该 DTO）。
 *
 * @deprecated 无使用方，仅作历史遗留保留，后续可删除
 */
@Deprecated
@Data
public class AgentDTO {

    private String agentId;

    private String name;

    /** 系统提示词 */
    private String systemPrompt;

    /** 模型标识，如 gpt-4o / deepseek-chat / qwen-plus */
    private String model;

    /** 可用工具名称列表 */
    private List<String> tools;
}
