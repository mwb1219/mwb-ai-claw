package com.mwb.ai.claw.domain.collaboration;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 协作结果：编排执行完成后的统一输出。
 */
@Data
public class CollaborationResult {

    /** 最终回复（routing=单 Agent 回复；conversational=收敛结论；delegate=汇总结论） */
    private String reply;

    /** 主导 Agent id */
    private String agentId;

    /** 会话 id（routing 使用主会话；协作编排为临时会话，最终为主会话） */
    private String sessionId;

    /** 实际使用的编排 id */
    private String orchestrationId;

    /** 执行轨迹（Thought / Action / Observation / 阶段 / 讨论轮次摘要） */
    private List<String> traceSteps = new ArrayList<>();
}
