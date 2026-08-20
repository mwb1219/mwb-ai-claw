package com.mwb.ai.claw.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * 指标记录门面（claw.* 命名空间）：统一记录 LLM / 工具 / ReAct / API / 记忆指标。
 * <p>
 * 由 {@code ClawCoreAutoConfiguration} 注册：优先使用 Spring 容器中的 {@link MeterRegistry}
 * （引入 actuator 时自动生效），否则兜底 {@code SimpleMeterRegistry} 内存计数，
 * 后续接入 Prometheus 只需额外引入 micrometer-registry-prometheus 并暴露 /actuator/prometheus。
 */
public class MetricsRecorder {

    private final MeterRegistry registry;

    public MetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    // ==================== LLM ====================

    public void llmRequest(String model, String status) {
        Counter.builder("claw.llm.request")
                .tag("model", model).tag("status", status).register(registry).increment();
    }

    public void llmDuration(String model, long ms) {
        Timer.builder("claw.llm.duration").tag("model", model)
                .register(registry).record(Duration.ofMillis(ms));
    }

    public void llmTokens(String model, String kind, long tokens) {
        if (tokens <= 0) {
            return;
        }
        Counter.builder("claw.llm.token")
                .tag("model", model).tag("kind", kind).register(registry).increment(tokens);
    }

    public void llmRetry(String model, int attempt) {
        Counter.builder("claw.llm.retry")
                .tag("model", model).tag("attempt", String.valueOf(attempt)).register(registry).increment();
    }

    // ==================== 工具 ====================

    public void toolExecute(String tool, String status) {
        Counter.builder("claw.tool.execute")
                .tag("tool", tool).tag("status", status).register(registry).increment();
    }

    public void toolDuration(String tool, long ms) {
        Timer.builder("claw.tool.duration").tag("tool", tool)
                .register(registry).record(Duration.ofMillis(ms));
    }

    public void toolTimeout(String tool) {
        Counter.builder("claw.tool.timeout").tag("tool", tool).register(registry).increment();
    }

    // ==================== ReAct / API ====================

    public void reactTurn(String status, int steps) {
        Counter.builder("claw.react.turn")
                .tag("status", status).tag("steps", String.valueOf(steps)).register(registry).increment();
    }

    public void apiRequest(String path, String status, long ms) {
        Counter.builder("claw.api.request")
                .tag("path", path).tag("status", status).register(registry).increment();
        Timer.builder("claw.api.duration").tag("path", path)
                .register(registry).record(Duration.ofMillis(ms));
    }

    // ==================== 记忆 ====================

    public void memorySynthesis(String type, String status) {
        Counter.builder("claw.memory.synthesis")
                .tag("type", type).tag("status", status).register(registry).increment();
    }
}
