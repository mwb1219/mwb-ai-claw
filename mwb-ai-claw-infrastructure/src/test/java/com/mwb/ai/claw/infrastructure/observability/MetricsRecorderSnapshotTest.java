package com.mwb.ai.claw.infrastructure.observability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * MetricsRecorder.snapshot() 单测：Counter / Timer 快照结构化输出。
 */
public class MetricsRecorderSnapshotTest {

    private MetricsRecorder recorder() {
        return new MetricsRecorder(new SimpleMeterRegistry());
    }

    private Map<String, Object> find(List<Map<String, Object>> meters, String name, String tags) {
        for (Map<String, Object> m : meters) {
            if (name.equals(m.get("name")) && tags.equals(m.get("tags"))) {
                return m;
            }
        }
        return null;
    }

    @Test
    public void testEmptyRegistry() {
        assertTrue(recorder().snapshot().isEmpty());
    }

    @Test
    public void testCounterSnapshot() {
        MetricsRecorder r = recorder();
        r.llmRequest("gpt-4", "success");
        r.llmRequest("gpt-4", "error");
        r.toolExecute("shell", "success");

        List<Map<String, Object>> meters = r.snapshot();
        assertEquals(3, meters.size());

        Map<String, Object> ok = find(meters, "claw.llm.request", "model=gpt-4,status=success");
        assertEquals("llm 成功请求计数", 1.0, ((Number) ok.get("count")).doubleValue(), 0.001);
        Map<String, Object> err = find(meters, "claw.llm.request", "model=gpt-4,status=error");
        assertEquals("llm 失败请求计数", 1.0, ((Number) err.get("count")).doubleValue(), 0.001);
    }

    @Test
    public void testTimerSnapshot() {
        MetricsRecorder r = recorder();
        r.llmDuration("gpt-4", 100);
        r.llmDuration("gpt-4", 300);

        List<Map<String, Object>> meters = r.snapshot();
        Map<String, Object> t = find(meters, "claw.llm.duration", "model=gpt-4");
        assertEquals("Timer 次数", 2L, ((Number) t.get("count")).longValue());
        assertEquals("Timer 总量", 400.0, ((Number) t.get("totalMs")).doubleValue(), 0.5);
        assertEquals("Timer 均值", 200.0, ((Number) t.get("meanMs")).doubleValue(), 0.5);
    }
}
