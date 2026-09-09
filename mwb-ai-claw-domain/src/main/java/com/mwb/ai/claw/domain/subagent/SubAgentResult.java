package com.mwb.ai.claw.domain.subagent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 子代理执行结果（H3 · Agent-as-Tool）：spawn 产出的结构化结果，作为工具 Observation 回传主 Agent。
 */
@Data
public class SubAgentResult {

    /** 子代理最终回复（可直接作为主 Agent 的 Observation） */
    private String reply;

    /** 步骤级轨迹（runAgentResult 已采集） */
    private List<String> traceSteps = new ArrayList<>();

    /** 实际推理步数 */
    private int stepsUsed;

    /** 本子代理累计 token 消耗 */
    private long tokens;

    /** 执行耗时（毫秒） */
    private long durationMs;

    /** 执行是否成功 */
    private boolean success = true;

    /** 失败原因（success=false 时有值） */
    private String error;

    /** 本次生成的子代理 id（sub-{uuid}） */
    private String agentId;

    /** 是否被取消（超时取消置 true） */
    private boolean cancelled;
}
