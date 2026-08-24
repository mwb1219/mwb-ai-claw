package com.mwb.ai.claw.infrastructure.observability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.observability.RunUsage;

/**
 * JDBC 版运行用量存储测试：写库 SQL 与按日期查询的字段映射（以 Mock JdbcTemplate 验证生成/映射）。
 */
public class JdbcRunUsageStoreTest {

    private JdbcTemplate jdbc;
    private JdbcRunUsageStore store;

    @Before
    public void setUp() {
        jdbc = mock(JdbcTemplate.class);
        store = new JdbcRunUsageStore(jdbc);
    }

    @Test
    public void saveInsertsRunUsageRow() {
        RunUsage u = usage("s1", true);
        store.save(u);
        verify(jdbc).update(contains("INSERT INTO claw_run_usage"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    public void findByDateMapsColumnsToDisplayFields() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("session_id", "s1");
        row.put("agent_id", "default");
        row.put("orchestration", "routing");
        row.put("model", "deepseek-chat");
        row.put("duration_ms", 1234L);
        row.put("success", true);
        row.put("steps", 3);
        row.put("error_code", null);
        row.put("create_time", 1_000_000L);
        when(jdbc.queryForList(anyString(), (Object[]) any()))
                .thenReturn(new ArrayList<>(Arrays.asList(row)));

        List<Map<String, Object>> runs = store.findByDate("2099-01-01");
        assertEquals(1, runs.size());
        Map<String, Object> e = runs.get(0);
        assertEquals("s1", e.get("sessionId"));
        assertEquals("deepseek-chat", e.get("model"));
        assertEquals(Boolean.TRUE, e.get("success"));
        assertEquals(1234L, e.get("durationMs"));
        assertEquals(3, e.get("steps"));
        assertTrue("ts 应为展示字段", e.containsKey("ts"));
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
}