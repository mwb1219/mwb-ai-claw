package com.mwb.ai.claw.web;

import javax.annotation.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mwb.ai.claw.domain.observability.TraceRun;
import com.mwb.ai.claw.domain.observability.TraceStore;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.dto.SingleResponse;

/**
 * 全链路 trace 查询接口：按 traceId 还原一次 Agent 执行的逐步明细（Thought / Action / Observation）。
 * <p>
 * 数据来自 {@code TraceStore}（本地 JSON 或 JDBC 落库），按当前请求身份（tenantId/userId）隔离过滤。
 */
@RestController
@RequestMapping("/trace")
@Profile("web")
public class TraceController {

    @Resource
    private ObjectProvider<TraceStore> traceStoreProvider;

    /**
     * 查询指定 traceId 的全链路步骤。未装配 TraceStore（trace.enabled=false）或越权时返回失败。
     */
    @GetMapping("/{traceId}")
    public SingleResponse<TraceRun> trace(@PathVariable String traceId) {
        TraceStore store = traceStoreProvider.getIfAvailable();
        if (store == null) {
            return SingleResponse.buildFailure("TRACE_DISABLED", "步骤级 trace 未启用");
        }
        TraceRun run = store.findTrace(AgentScopeContext.get(), traceId);
        if (run == null) {
            return SingleResponse.buildFailure("TRACE_NOT_FOUND", "未找到 trace: " + traceId);
        }
        return SingleResponse.of(run);
    }
}