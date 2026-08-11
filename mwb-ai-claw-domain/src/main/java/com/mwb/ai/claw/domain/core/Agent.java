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

    /** 模型配置 */
    private ModelConfig modelConfig;

    /** 可用工具名称列表 */
    private List<String> toolNames = new ArrayList<>();

    /** ReAct 最大推理步数 */
    private int maxSteps = 8;
}
