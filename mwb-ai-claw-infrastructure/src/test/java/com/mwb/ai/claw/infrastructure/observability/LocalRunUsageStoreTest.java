package com.mwb.ai.claw.infrastructure.observability;

import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.mwb.ai.claw.domain.observability.RunUsage;
import com.mwb.ai.claw.domain.scope.AgentScope;
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

    private RunUsage usage(String tenantId, String userId, String sessionId, boolean success) {
        RunUsage u = new RunUsage();
        u.setTenantId(tenantId);
        u.setUserId(userId);
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
    public void saveThenReadBackByDate_withScopeIsolation() throws Exception {
        LocalRunUsageStore store = store();
        // 写入两条 tenant=A/user=1 的数据 + 一条 tenant=B/user=2（应被 scope=A:1 过滤掉）
        store.save(usage("tA", "u1", "s1", true));
        store.save(usage("tA", "u1", "s2", false));
        store.save(usage("tB", "u2", "s3", true));  // 其他租户数据

        // scope = tA/u1：只应返回 2 条
        List<Map<String, Object>> runs = store.findByDate(AgentScope.of("tA", "u1"), null);
        assertEquals(2, runs.size());
        assertEquals("s1", runs.get(0).get("sessionId"));
        assertEquals(Boolean.TRUE, runs.get(0).get("success"));
        assertEquals("s2", runs.get(1).get("sessionId"));
        assertEquals(Boolean.FALSE, runs.get(1).get("success"));
        assertEquals("tA", runs.get(0).get("tenantId"));
        assertEquals("u1", runs.get(0).get("userId"));
        assertTrue("ts 应为 ISO 字符串", runs.get(0).get("ts") instanceof String);

        // scope = tB/u2：只应返回 1 条
        List<Map<String, Object>> runsB = store.findByDate(AgentScope.of("tB", "u2"), null);
        assertEquals(1, runsB.size());
        assertEquals("s3", runsB.get(0).get("sessionId"));

        // scope = 默认空间：无匹配记录（所有记录都有 tX/uX）
        List<Map<String, Object>> runsDefault = store.findByDate(null, null);
        assertTrue("默认 scope 不应读到任何带 tX/uX 记录", runsDefault.isEmpty());
    }

    @Test
    public void absentDateReturnsEmpty() throws Exception {
        assertTrue(store().findByDate(AgentScope.of("tA", "u1"), "2099-01-01").isEmpty());
    }
}