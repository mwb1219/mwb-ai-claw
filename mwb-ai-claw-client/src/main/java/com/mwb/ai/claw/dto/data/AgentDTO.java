package com.mwb.ai.claw.dto.data;

import lombok.Data;

import java.util.List;

/**
 * Agent 配置 DTO
 */
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
