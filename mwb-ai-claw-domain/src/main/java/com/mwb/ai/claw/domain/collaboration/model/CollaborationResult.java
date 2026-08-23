package com.mwb.ai.claw.domain.collaboration;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

import com.mwb.ai.claw.domain.core.ErrorCategory;

/**
 * 协作结果：编排执行完成后的统一输出。
 */
@Data
public class CollaborationResult {

    /** 最终回复（routing=单 Agent 回复；conversational=收敛结论；delegate=汇总结论） */
    private String reply;

    /** 执行是否成功（LLM error 终态 / 预算耗尽时置 false） */
    private boolean success = true;

    /** 失败时的明确错误信息（success=false 时有值） */
    private String errorMessage;

    /** 失败时的错误分类（success=false 时有值，供上层映射错误码） */
    private ErrorCategory errorCategory;

    /** 主导 Agent id */
    private String agentId;

    /** 会话 id（routing 使用主会话；协作编排为临时会话，最终为主会话） */
    private String sessionId;

    /** 实际使用的编排 id */
    private String orchestrationId;

    /** 执行轨迹（Thought / Action / Observation / 阶段 / 讨论轮次摘要） */
    private List<String> traceSteps = new ArrayList<>();
}
