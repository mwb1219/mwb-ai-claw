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

    /** 最终回复内容 */
    private String reply;

    /** 推理执行轨迹（Thought / Action / Observation 摘要） */
    private List<String> traceSteps = new ArrayList<>();
}
