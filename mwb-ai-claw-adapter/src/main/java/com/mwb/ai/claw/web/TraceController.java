package com.mwb.ai.claw.web;

import javax.annotation.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mwb.ai.claw.domain.observability.TraceRun;
import com.mwb.ai.claw.domain.observability.TraceStore;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.dto.SingleResponse;

/**
 * 全链路 trace 查询接口：按 traceId 还原一次 Agent 执行的逐步明细（Thought / Action / Observation）。
 * <p>
 * 数据来自 {@code TraceStore}（本地 JSON 或 JDBC 落库），按当前请求身份（tenantId/userId）隔离过滤。
 * {@code expand=true} 时递归聚合跨实例/嵌套编排链路（T5）：以 {@code parentTraceId} 字段关联，
 * 将所有子 trace 组装为树状结构并挂到 {@code children} 字段。
 */
@RestController
@RequestMapping("/trace")
@Profile("web")
public class TraceController {

    @Resource
    private ObjectProvider<TraceStore> traceStoreProvider;

    /**
     * 查询指定 traceId 的全链路步骤。未装配 TraceStore（trace.enabled=false）或越权时返回失败。
     *
     * @param expand 是否递归聚合子 trace 构成完整调用树（默认 false）
     */
    @GetMapping("/{traceId}")
    public SingleResponse<TraceRun> trace(@PathVariable String traceId,
                                          @RequestParam(value = "expand", defaultValue = "false") boolean expand) {
        TraceStore store = traceStoreProvider.getIfAvailable();
        if (store == null) {
            return SingleResponse.buildFailure("TRACE_DISABLED", "步骤级 trace 未启用");
        }
        AgentScope scope = AgentScopeContext.get();
        TraceRun run = store.findTrace(scope, traceId);
        if (run == null) {
            return SingleResponse.buildFailure("TRACE_NOT_FOUND", "未找到 trace: " + traceId);
        }
        if (expand) {
            attachChildren(store, scope, run);
        }
        return SingleResponse.of(run);
    }

    /** 递归将直接子 trace 挂到 children 字段（最多展开 10 层防环） */
    private void attachChildren(TraceStore store, AgentScope scope, TraceRun run) {
        attachChildren(store, scope, run, 0);
    }

    private void attachChildren(TraceStore store, AgentScope scope, TraceRun run, int depth) {
        if (depth > 10 || run == null || run.getTraceId() == null) {
            return;
        }
        java.util.List<TraceRun> children = store.findChildren(scope, run.getTraceId());
        if (children == null || children.isEmpty()) {
            return;
        }
        run.setChildren(children);
        for (TraceRun child : children) {
            attachChildren(store, scope, child, depth + 1);
        }
    }
}