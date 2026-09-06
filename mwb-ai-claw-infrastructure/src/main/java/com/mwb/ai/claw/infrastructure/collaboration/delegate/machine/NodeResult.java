package com.mwb.ai.claw.infrastructure.collaboration.delegate.machine;

/**
 * 节点执行结果（最终回复 + 主导 Agent id + 是否失败标记）
 */
public class NodeResult {
    public final String reply;
    public final String agentId;
    public final boolean failed;

    public NodeResult(String reply, String agentId, boolean failed) {
        this.reply = reply;
        this.agentId = agentId;
        this.failed = failed;
    }
}