package com.mwb.ai.claw.domain.observability;

import lombok.Data;

/**
 * 一次 Agent 执行的运行用量摘要（每次运行一条），供排障与成本核算。
 * <p>
 * 与步骤级 {@code TraceRun}（一次运行多步明细）互补：运行摘要是「一行 」的汇总。
 * 存储横切 local（JSONL 文件）与 db（{@code claw_run_usage} 表，多实例共享，生产推荐）。
 */
@Data
public class RunUsage {

    /** 关联的全链路 trace id（可经 GET /trace/{traceId} 还原本次执行的逐步明细） */
    private String traceId;

    /** 会话 id */
    private String sessionId;

    /** 主导 Agent id */
    private String agentId;

    /** 实际使用的编排 id */
    private String orchestration;

    /** 使用的模型 */
    private String model;

    /** 执行耗时（毫秒） */
    private long durationMs;

    /** 执行是否成功 */
    private boolean success = true;

    /** 步骤条数 */
    private int steps;

    /** 失败错误码（成功为空） */
    private String errorCode;

    /** 记录/发生时间戳（epoch 毫秒），由记录方填充 */
    private long createTime;
}