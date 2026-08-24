package com.mwb.ai.claw.infrastructure.tool;

import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.rag.context.RagRequestContext;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.domain.tool.DynamicToolRegistry;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.tool.ToolPermissionChecker;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.exception.BizException;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具网关实现：自动收集所有 ToolExecutor Bean，按名称路由执行。
 * <p>
 * 同时实现 {@link DynamicToolRegistry}，支持运行时动态注册工具（如 MCP 工具）。
 * <p>
 * 统一执行超时兜底（C3）：每个工具调用包装为 {@link Future#get(timeoutSeconds)}，
 * 超时 → 取消执行线程 → 返回明确错误（记 {@code claw.tool.timeout}），MCP / 内置 / 自定义
 * 工具超时行为一致；异常按 {@link BizException}（业务，不重试）与其他异常分类。
 * <p>
 * 由 {@code ClawCoreAutoConfiguration} 以 {@code @ConditionalOnMissingBean} 注册，使用方可覆盖。
 */
public class ToolGatewayImpl implements ToolGateway, DynamicToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolGatewayImpl.class);

    private final Map<String, ToolExecutor> executors = new ConcurrentHashMap<>();

    private final ToolPermissionChecker permissionChecker;

    /** 指标记录（可为 null） */
    private final MetricsRecorder metrics;

    /** 单个工具执行超时（秒） */
    private final int timeoutSeconds;

    /** 错误信息最大长度（超出截断，复用 max-output-length） */
    private final int maxOutputLength;

    /** 工具执行线程池（daemon，随 JVM 退出；每次调用提交后等待超时） */
    private final ExecutorService toolPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tool-exec");
        t.setDaemon(true);
        return t;
    });

    public ToolGatewayImpl(List<ToolExecutor> executorList, ToolPermissionChecker permissionChecker) {
        this(executorList, permissionChecker, null, 30, 10000);
    }

    public ToolGatewayImpl(List<ToolExecutor> executorList, ToolPermissionChecker permissionChecker,
                           MetricsRecorder metrics) {
        this(executorList, permissionChecker, metrics, 30, 10000);
    }

    public ToolGatewayImpl(List<ToolExecutor> executorList, ToolPermissionChecker permissionChecker,
                           MetricsRecorder metrics, int timeoutSeconds, int maxOutputLength) {
        if (executorList != null) {
            for (ToolExecutor executor : executorList) {
                executors.put(executor.getName(), executor);
            }
        }
        this.permissionChecker = permissionChecker;
        this.metrics = metrics;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 30;
        this.maxOutputLength = maxOutputLength > 0 ? maxOutputLength : 10000;
    }

    @Override
    public ToolResult execute(String toolName, String argumentsJson) {
        return execute(toolName, argumentsJson, null);
    }

    @Override
    public ToolResult execute(String toolName, String argumentsJson, ProgressCallback callback) {
        long start = System.currentTimeMillis();
        // 静态授权（与人工审批门分层）：无权直接拒绝，不中断 ReAct
        if (permissionChecker != null && !permissionChecker.isAllowed(AgentScopeContext.get(), toolName)) {
            recordTool(toolName, "denied", start);
            return ToolResult.error("无权限调用工具: " + toolName);
        }
        ToolExecutor executor = executors.get(toolName);
        if (executor == null) {
            recordTool(toolName, "not_found", start);
            return ToolResult.error("工具不存在: " + toolName);
        }
        Future<ToolResult> future = null;
        // 捕获请求线程的 AgentScope：ThreadLocal 不跨线程传播，需显式带入工具执行线程
        // （业务工具基于 AgentScopeContext 读 tenantId/userId 实现租户隔离）
        AgentScope scope = AgentScopeContext.get();
        try {
            // 统一执行超时兜底：包装到线程池执行，超时 → 取消执行线程
            future = toolPool.submit(RagRequestContext.wrapCallable(() -> {
                AgentScopeContext.set(scope);
                try {
                    return executor.execute(argumentsJson, callback);
                } finally {
                    AgentScopeContext.clear();
                }
            }));
            ToolResult result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            recordTool(toolName, result.isSuccess() ? "success" : "error", start);
            return result;
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            log.warn("工具执行超时: tool={}, timeout={}s", toolName, timeoutSeconds);
            recordTool(toolName, "timeout", start);
            if (metrics != null) {
                metrics.toolTimeout(toolName);
            }
            return ToolResult.error(truncate("工具执行超时(" + timeoutSeconds + "s): " + toolName));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordTool(toolName, "interrupted", start);
            return ToolResult.error(truncate("工具执行被中断: " + toolName));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof BizException) {
                // 业务异常（权限 / 校验等）：不回传堆栈，Observation 反馈给 LLM 调整
                log.warn("工具业务失败: tool={}, err={}", toolName, cause.getMessage());
                recordTool(toolName, "error", start);
                return ToolResult.error(truncate("工具执行失败: " + cause.getMessage()));
            }
            log.error("工具执行异常: tool={}, err={}", toolName, cause.getMessage(), cause);
            recordTool(toolName, "exception", start);
            return ToolResult.error(truncate("工具执行异常: " + cause.getMessage()));
        }
    }

    /** 错误信息截断：复用 max-output-length，避免超长错误撑爆 Observation / 日志 */
    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > maxOutputLength ? text.substring(0, maxOutputLength) + "..." : text;
    }

    private void recordTool(String toolName, String status, long start) {
        if (metrics != null) {
            metrics.toolExecute(toolName, status);
            metrics.toolDuration(toolName, System.currentTimeMillis() - start);
        }
    }

    @Override
    public List<ToolSpec> listTools() {
        List<ToolSpec> list = new ArrayList<>();
        for (ToolExecutor executor : executors.values()) {
            list.add(executor.getSpec());
        }
        return list;
    }

    @Override
    public ToolSpec getToolSpec(String toolName) {
        ToolExecutor executor = executors.get(toolName);
        return executor == null ? null : executor.getSpec();
    }

    @Override
    public void registerExecutor(ToolExecutor executor) {
        executors.put(executor.getName(), executor);
    }

    @Override
    public void unregisterExecutor(String toolName) {
        executors.remove(toolName);
    }
}
