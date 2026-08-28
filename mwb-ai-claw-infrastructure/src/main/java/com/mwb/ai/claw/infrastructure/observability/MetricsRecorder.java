package com.mwb.ai.claw.infrastructure.observability;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

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

    // ==================== 提炼任务队列（Phase 1） ====================

    public void synthLockAcquireFail(String kind, String reason) {
        Counter.builder("claw.synth.lock.acquire.fail")
                .tag("kind", kind).tag("reason", reason).register(registry).increment();
    }

    public void synthLockWait(String kind, String result, long ms) {
        Timer.builder("claw.synth.lock.wait").tag("kind", kind).tag("result", result)
                .register(registry).record(Duration.ofMillis(ms));
    }

    public void synthDuplicateWrite(String pageType) {
        Counter.builder("claw.synth.duplicate.write")
                .tag("page_type", pageType).register(registry).increment();
    }

    public void synthLlmSkip(String kind, String reason) {
        Counter.builder("claw.synth.llm.skip")
                .tag("kind", kind).tag("reason", reason).register(registry).increment();
    }

    public void synthPendingGauge(int pending, String queueType) {
        Gauge.builder("claw.synth.task.pending", () -> pending)
                .tag("queue_type", queueType).register(registry);
    }

    // ==================== 查询 ====================

    /**
     * 指标快照：遍历注册表全部 claw.* 指标，返回结构化的条目列表（供 shell /metrics 面板展示）。
     * <p>
     * 每项包含 {@code name} / {@code tags}（"k=v,k2=v2"），以及按类型补充的数值：
     * Counter → {@code count}；Timer → {@code count}+{@code totalMs}+{@code meanMs}；
     * 其余类型 → {@code value}。
     */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> meters = new ArrayList<>();
        for (Meter meter : registry.getMeters()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", meter.getId().getName());
            StringBuilder tags = new StringBuilder();
            for (Tag tag : meter.getId().getTagsAsIterable()) {
                if (tags.length() > 0) {
                    tags.append(',');
                }
                tags.append(tag.getKey()).append('=').append(tag.getValue());
            }
            m.put("tags", tags.toString());
            if (meter instanceof Counter) {
                m.put("count", ((Counter) meter).count());
            } else if (meter instanceof Timer) {
                Timer timer = (Timer) meter;
                long count = timer.count();
                m.put("count", count);
                m.put("totalMs", timer.totalTime(TimeUnit.MILLISECONDS));
                m.put("meanMs", count == 0 ? 0.0 : timer.totalTime(TimeUnit.MILLISECONDS) / count);
            } else if (meter instanceof Gauge) {
                m.put("value", ((Gauge) meter).value());
            } else {
                m.put("value", meter.measure().iterator().next().getValue());
            }
            meters.add(m);
        }
        return meters;
    }
}
