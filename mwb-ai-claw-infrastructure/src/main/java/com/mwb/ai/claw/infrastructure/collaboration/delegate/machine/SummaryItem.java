package com.mwb.ai.claw.infrastructure.collaboration.delegate.machine;

/**
 * 汇总注入条目（todoId + 执行 Agent id + 注入内容），供 top-k 相关性压缩排序
 */
public class SummaryItem {
    public final String todoId;
    public final String agentId;
    public final String content;

    public SummaryItem(String todoId, String agentId, String content) {
        this.todoId = todoId;
        this.agentId = agentId;
        this.content = content;
    }
}