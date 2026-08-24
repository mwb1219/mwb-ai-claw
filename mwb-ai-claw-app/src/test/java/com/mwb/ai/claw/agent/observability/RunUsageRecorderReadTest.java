package com.mwb.ai.claw.agent.observability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.mwb.ai.claw.domain.observability.RunUsage;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

/**
 * RunUsageRecorder.readRuns() 单测：JSONL 落盘读取 + 日期过滤。
 */
public class RunUsageRecorderReadTest {

    private Path tempDir;
    private RunUsageRecorder recorder;

    @Before
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("mwb-runs-");
        AgentProperties props = new AgentProperties();
        props.getObservability().setRunUsageDir(tempDir.toString());
        recorder = new RunUsageRecorder(props);
    }

    @After
    public void tearDown() throws Exception {
        // 递归删除临时目录（含已写入的 JSONL 文件）
        if (Files.exists(tempDir)) {
            Files.walk(tempDir).sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignore) {
                        }
                    });
        }
    }

    private RunUsage usage(String sessionId, boolean success) {
        RunUsage u = new RunUsage();
        u.setSessionId(sessionId);
        u.setAgentId("default");
        u.setModel("test-model");
        u.setDurationMs(1234L);
        u.setSuccess(success);
        u.setSteps(3);
        return u;
    }

    @Test
    public void testReadWrittenRuns() {
        recorder.record(usage("s1", true));
        recorder.record(usage("s2", false));

        List<Map<String, Object>> runs = recorder.readRuns(LocalDate.now().toString());
        assertEquals("应读取到 2 条运行记录", 2, runs.size());
        assertEquals("s1", runs.get(0).get("sessionId"));
        assertEquals(Boolean.TRUE, runs.get(0).get("success"));
        assertEquals("s2", runs.get(1).get("sessionId"));
        assertEquals(Boolean.FALSE, runs.get(1).get("success"));
        assertEquals("test-model", runs.get(0).get("model"));
    }

    @Test
    public void testEmptyDateReturnsEmpty() {
        assertTrue("不存在的日期应返回空列表", recorder.readRuns("2099-01-01").isEmpty());
    }

    @Test
    public void testNullDateDefaultsToday() {
        recorder.record(usage("s1", true));
        assertEquals(1, recorder.readRuns(null).size());
        assertEquals(1, recorder.readRuns("").size());
    }
}
