package com.mwb.ai.claw.domain.observability;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 一次 Agent 运行的全链路 trace：携带 traceId，可还原该次执行的逐步输入输出。
 * <p>
 * 与 {@code RunUsage} 运行摘要（一次一行）互补：本对象保存步骤级明细，
 * 由 {@link TraceStore} 落库，生产环境建议切到 db 使多实例共享同一份 trace 数据。
 */
@Data
public class TraceRun {

    /** 链路 trace id（贯穿请求日志 MDC 与落库） */
    private String traceId;

    /** 租户 id（空串=默认空间，用于租户隔离查询） */
    private String tenantId = "";

    /** 用户 id（空串=默认空间，用于租户隔离查询） */
    private String userId = "";

    /** 会话 id */
    private String sessionId;

    /** 主导 Agent id */
    private String agentId;

    /** 实际使用的编排 id */
    private String orchestration;

    /** 使用的模型 */
    private String model;

    /** 执行开始时间戳（epoch 毫秒） */
    private long startTime;

    /** 执行耗时（毫秒） */
    private long durationMs;

    /** 执行是否成功 */
    private boolean success = true;

    /** 失败时的错误码（成功时为 null） */
    private String errorCode;

    /** 步骤级明细（Thought / Action / Observation / Info） */
    private List<TraceStep> steps = new ArrayList<>();
}