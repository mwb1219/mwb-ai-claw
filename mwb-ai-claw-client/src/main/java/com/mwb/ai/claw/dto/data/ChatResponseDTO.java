package com.mwb.ai.claw.dto.data;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 对话响应 DTO
 */
@Data
public class ChatResponseDTO {

    /** 会话 ID */
    private String sessionId;

    /** 实际处理本次对话的 Agent ID */
    private String agentId;

    /** 实际使用的编排 id（routing | pipeline | ...） */
    private String orchestrationId;

    /** 最终回复内容 */
    private String reply;

    /** 推理执行轨迹（Thought / Action / Observation 摘要） */
    private List<String> traceSteps = new ArrayList<>();
}
