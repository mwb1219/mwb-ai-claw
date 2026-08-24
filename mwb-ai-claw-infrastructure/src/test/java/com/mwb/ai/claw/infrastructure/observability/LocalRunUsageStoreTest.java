package com.mwb.ai.claw.infrastructure.observability;

import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.mwb.ai.claw.domain.observability.RunUsage;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

/**
 * 本地文件版运行用量存储测试：JSONL 逐行落盘 + 按日期读取。
 * 以临时目录作为 run-usage-dir，不污染真实数据。
 */
public class LocalRunUsageStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private LocalRunUsageStore store() throws Exception {
        AgentProperties p = new AgentProperties();
        p.getObservability().setRunUsageDir(tmp.getRoot().getAbsolutePath());
        return new LocalRunUsageStore(p);
    }

    private RunUsage usage(String sessionId, boolean success) {
        RunUsage u = new RunUsage();
        u.setSessionId(sessionId);
        u.setAgentId("default");
        u.setModel("test-model");
        u.setDurationMs(1234L);
        u.setSuccess(success);
        u.setSteps(3);
        u.setCreateTime(System.currentTimeMillis());
        return u;
    }

    @Test
    public void saveThenReadBackByDate() throws Exception {
        LocalRunUsageStore store = store();
        store.save(usage("s1", true));
        store.save(usage("s2", false));

        List<Map<String, Object>> runs = store.findByDate(null);
        assertEquals(2, runs.size());
        assertEquals("s1", runs.get(0).get("sessionId"));
        assertEquals(Boolean.TRUE, runs.get(0).get("success"));
        assertEquals("s2", runs.get(1).get("sessionId"));
        assertEquals(Boolean.FALSE, runs.get(1).get("success"));
        assertTrue("ts 应为 ISO 字符串", runs.get(0).get("ts") instanceof String);
    }

    @Test
    public void absentDateReturnsEmpty() throws Exception {
        assertTrue(store().findByDate("2099-01-01").isEmpty());
    }
}