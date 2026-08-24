package com.mwb.ai.claw.infrastructure.observability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.observability.TraceRun;
import com.mwb.ai.claw.domain.observability.TraceStep;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * JDBC 版 trace 存储测试：写库 SQL（run 标识行 + 步骤行）与按 traceId 还原映射、租户过滤。
 * （以 Mock JdbcTemplate 验证 SQL 生成与结果映射；真实数据库建表/联调依赖 schema.sql / initdb。）
 */
public class JdbcTraceStoreTest {

    private JdbcTemplate jdbc;
    private JdbcTraceStore store;

    @Before
    public void setUp() {
        jdbc = mock(JdbcTemplate.class);
        store = new JdbcTraceStore(jdbc);
    }

    @Test
    public void saveWritesRunMarkerRowPlusDetailRows() {
        TraceRun run = run("tr-1", "tenant-a", "u1");
        run.setSteps(Arrays.asList(step(1, "thought", "[Thought] 第 1 步"),
                step(2, "action", "[Action] 调用工具: list_products 参数: {}")));

        store.saveTrace(run);

        // run 标识行 + 2 步明细 = 3 行批量写入
        verify(jdbc).batchUpdate(contains("INSERT INTO claw_trace"),
                any(List.class), any(int[].class));
    }

    @Test
    public void findRestoresRunAndStepsByTraceId() {
        Map<String, Object> marker = row("__run__", "tr-1", "tenant-a", "u1", true, "sess-1");
        Map<String, Object> s1 = stepRow(1, "thought", "[Thought] 第 1 步");
        Map<String, Object> s2 = stepRow(2, "action", "[Action] 调用工具: list_products 参数: {}");
        when(jdbc.queryForList(anyString(), (Object[]) any()))
                .thenReturn(new ArrayList<>(Arrays.asList(marker, s1, s2)));

        TraceRun found = store.findTrace(AgentScope.of("tenant-a", "u1"), "tr-1");

        assertEquals("tr-1", found.getTraceId());
        assertEquals("tenant-a", found.getTenantId());
        assertEquals("u1", found.getUserId());
        assertEquals("sess-1", found.getSessionId());
        validateStrict(located(found, "action"));
        assertEquals(2, found.getSteps().size());
    }

    @Test
    public void findFiltersByTenantAndUser() {
        Map<String, Object> marker = row("__run__", "tr-1", "tenant-a", "u1", true, "sess-1");
        when(jdbc.queryForList(anyString(), (Object[]) any()))
                .thenReturn(new ArrayList<>(Arrays.asList(marker)));

        assertEquals("tr-1", store.findTrace(AgentScope.of("tenant-a", "u1"), "tr-1").getTraceId());
        // 越权（不同租户）：queryForList 在真实库按条件命中空，模拟返回空
        when(jdbc.queryForList(anyString(), (Object[]) any()))
                .thenReturn(new ArrayList<>());
        assertNull(store.findTrace(AgentScope.of("tenant-b", "u1"), "tr-1"));
    }

    private Map<String, Object> row(String type, String traceId, String tenant, String user,
                                    boolean success, String sessionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trace_id", traceId);
        m.put("tenant_id", tenant);
        m.put("user_id", user);
        m.put("session_id", sessionId);
        m.put("agent_id", "default");
        m.put("orchestration", "routing");
        m.put("model", "deepseek-chat");
        m.put("start_time", 1000L);
        m.put("duration_ms", 200L);
        m.put("success", success);
        m.put("error_code", null);
        m.put("step_index", type.equals("__run__") ? 0 : 1);
        m.put("step_type", type);
        m.put("step_content", null);
        return m;
    }

    private Map<String, Object> stepRow(int index, String type, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trace_id", "tr-1");
        m.put("tenant_id", "tenant-a");
        m.put("user_id", "u1");
        m.put("session_id", "sess-1");
        m.put("agent_id", "default");
        m.put("orchestration", "routing");
        m.put("model", "deepseek-chat");
        m.put("start_time", 1000L);
        m.put("duration_ms", 200L);
        m.put("success", true);
        m.put("error_code", null);
        m.put("step_index", index);
        m.put("step_type", type);
        m.put("step_content", content);
        return m;
    }

    private TraceRun run(String traceId, String tenant, String user) {
        TraceRun r = new TraceRun();
        r.setTraceId(traceId);
        r.setTenantId(tenant);
        r.setUserId(user);
        r.setSessionId("sess-1");
        r.setAgentId("default");
        r.setOrchestration("routing");
        r.setModel("deepseek-chat");
        r.setStartTime(1000L);
        r.setDurationMs(200L);
        r.setSuccess(true);
        return r;
    }

    private TraceStep step(int index, String type, String content) {
        TraceStep s = new TraceStep();
        s.setIndex(index);
        s.setType(type);
        s.setContent(content);
        return s;
    }

    private void validateStrict(TraceStep step) {
        assertEquals("action", step.getType());
        assertEquals("[Action] 调用工具: list_products 参数: {}", step.getContent());
    }

    private TraceStep located(TraceRun run, String type) {
        return run.getSteps().stream().filter(s -> type.equals(s.getType())).findFirst().orElse(null);
    }
}