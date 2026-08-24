package com.mwb.ai.claw.infrastructure.observability;

import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.mwb.ai.claw.domain.observability.TraceRun;
import com.mwb.ai.claw.domain.observability.TraceStep;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

/**
 * 本地文件版 trace 存储测试：保存/读取、步骤明细还原、租户隔离。
 * 以临时目录作为 trace.dir，不污染真实数据。
 */
public class LocalTraceStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void saveAndFindReturnsFullTraceWithSteps() throws Exception {
        LocalTraceStore store = store();
        TraceStep s1 = step(1, "thought", "[Thought] 需要调用工具处理（第 1 步）");
        TraceStep s2 = step(2, "action", "[Action] 调用工具: list_products 参数: {}");
        TraceRun original = run("trace-abc", "tenant-a", "u1");
        original.setSteps(Arrays.asList(s1, s2));

        store.saveTrace(original);

        TraceRun found = store.findTrace(scope("tenant-a", "u1"), "trace-abc");
        assertNotNull(found);
        assertEquals("trace-abc", found.getTraceId());
        assertEquals("tenant-a", found.getTenantId());
        assertEquals("u1", found.getUserId());
        assertEquals(2, found.getSteps().size());
        assertEquals("action", found.getSteps().get(1).getType());
        assertEquals("[Action] 调用工具: list_products 参数: {}", found.getSteps().get(1).getContent());
    }

    @Test
    public void tenantIsolationReturnsNullWhenScopeMismatch() throws Exception {
        LocalTraceStore store = store();
        store.saveTrace(run("trace-x", "tenant-a", "u1"));

        assertNull(store.findTrace(scope("tenant-b", "u1"), "trace-x"));
        assertNull(store.findTrace(scope("tenant-a", "u2"), "trace-x"));
        assertNull(store.findTrace(null, "trace-x"));
    }

    @Test
    public void absentTraceIdReturnsNull() throws Exception {
        assertNull(store().findTrace(scope("tenant-a", "u1"), "no-such-trace"));
    }

    @Test
    public void defaultScopeUsesEmptyString() throws Exception {
        LocalTraceStore store = store();
        store.saveTrace(run("trace-d", "", ""));
        assertNotNull(store.findTrace(scope("", ""), "trace-d"));
    }

    private LocalTraceStore store() throws Exception {
        AgentProperties p = new AgentProperties();
        p.getObservability().getTrace().setDir(tmp.getRoot().getAbsolutePath());
        return new LocalTraceStore(p);
    }

    private TraceStep step(int index, String type, String content) {
        TraceStep s = new TraceStep();
        s.setIndex(index);
        s.setType(type);
        s.setContent(content);
        return s;
    }

    private TraceRun run(String traceId, String tenant, String user) {
        TraceRun r = new TraceRun();
        r.setTraceId(traceId);
        r.setTenantId(tenant);
        r.setUserId(user);
        r.setSessionId("sess-1");
        r.setAgentId("default");
        r.setOrchestration("routing");
        r.setSuccess(true);
        return r;
    }

    private AgentScope scope(String tenant, String user) {
        return AgentScope.of(tenant, user);
    }
}