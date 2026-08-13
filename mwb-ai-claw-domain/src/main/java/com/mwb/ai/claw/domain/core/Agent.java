package com.mwb.ai.claw.domain.core;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 实体：封装一个智能体的配置与人设
 */
@Data
public class Agent {

    private String agentId;

    private String name;

    /** 系统提示词 */
    private String systemPrompt;

    /** AGENT.md 扩展指令（文件式长期记忆，在 systemPrompt 之后追加） */
    private String agentInstructions;

    /** 能力描述（供 LLM 路由判断意图使用） */
    private String description;

    /** 规则路由关键词（供规则路由匹配使用） */
    private List<String> keywords = new ArrayList<>();

    /** 模型配置 */
    private ModelConfig modelConfig;

    /** 可用工具名称列表 */
    private List<String> toolNames = new ArrayList<>();

    /** ReAct 最大推理步数 */
    private int maxSteps = 8;
}
