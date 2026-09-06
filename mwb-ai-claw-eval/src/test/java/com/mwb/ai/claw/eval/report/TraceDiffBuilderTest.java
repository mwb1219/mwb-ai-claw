package com.mwb.ai.claw.eval.report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.mwb.ai.claw.domain.observability.TraceRun;
import com.mwb.ai.claw.domain.observability.TraceStep;
import com.mwb.ai.claw.eval.model.TraceDiffStatus;

/**
 * {@link TraceDiffBuilder} 单元测试：覆盖 identical / 缺失关键 action / 新增步骤 / 数字归一化 / 空基线。
 */
public class TraceDiffBuilderTest {

    private TraceStep step(int idx, String type, String content) {
        TraceStep s = new TraceStep();
        s.setIndex(idx);
        s.setType(type);
        s.setContent(content);
        return s;
    }

    private TraceRun run(List<TraceStep> steps) {
        TraceRun r = new TraceRun();
        r.setTraceId("t");
        r.setSteps(steps);
        return r;
    }

    /** 构造典型的「思考→工具调用→观察→回答」轨迹。 */
    private List<TraceStep> typicalSteps() {
        return Arrays.asList(
                step(1, "thought", "[Thought] 需要查询天气"),
                step(2, "action", "[Action] 调用工具: weather city=beijing"),
                step(3, "observation", "[Observation] 晴 25 度"),
                step(4, "action", "[Action] 调用工具: final_answer"));
    }

    @Test
    public void compare_identical_unchanged() {
        TraceDiff diff = TraceDiffBuilder.compare(run(typicalSteps()), run(typicalSteps()));
        assertEquals(TraceDiffStatus.UNCHANGED, diff.getStatus());
        assertNotNull(diff.getDetails());
    }

    @Test
    public void compare_missingCriticalAction_regressed() {
        List<TraceStep> baseline = typicalSteps();
        List<TraceStep> current = Arrays.asList(baseline.get(0), baseline.get(2), baseline.get(3)); // 缺 action
        TraceDiff diff = TraceDiffBuilder.compare(run(baseline), run(current));
        assertEquals(TraceDiffStatus.REGRESSED, diff.getStatus());
        assertTrue(diff.getDetails().contains("action"));
    }

    @Test
    public void compare_addedStep_improved() {
        List<TraceStep> current = typicalSteps();
        List<TraceStep> baseline = Arrays.asList(
                step(1, "thought", "[Thought] 需要查询天气"),
                step(2, "action", "[Action] 调用工具: final_answer"));
        TraceDiff diff = TraceDiffBuilder.compare(run(baseline), run(current));
        assertEquals(TraceDiffStatus.IMPROVED, diff.getStatus());
    }

    @Test
    public void compare_numberNormalization_ignoresConcreteValues() {
        List<TraceStep> baseline = Arrays.asList(
                step(1, "action", "[Action] 调用工具: search q=今日气温 北京"),
                step(2, "observation", "[Observation] 温度 25 度"));
        List<TraceStep> current = Arrays.asList(
                step(1, "action", "[Action] 调用工具: search q=今日气温 北京"),
                step(2, "observation", "[Observation] 温度 999 度")); // 仅数字不同
        TraceDiff diff = TraceDiffBuilder.compare(run(baseline), run(current));
        assertEquals(TraceDiffStatus.UNCHANGED, diff.getStatus());
    }

    @Test
    public void compare_emptyBaselineVersusNonEmpty_improved() {
        TraceDiff diff = TraceDiffBuilder.compare(run(Collections.emptyList()), run(typicalSteps()));
        assertEquals(TraceDiffStatus.IMPROVED, diff.getStatus());
    }
}