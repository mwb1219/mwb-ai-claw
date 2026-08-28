package com.mwb.ai.claw.infrastructure.observability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mwb.ai.claw.domain.observability.RunUsage;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * JDBC 版运行用量存储测试：写库 SQL 与按日期查询的字段映射（Mockito 验证 save；
 * findByDate 用 JdbcTemplate 子类记录 SQL/参数，绕开 Mockito+varargs 的签名匹配歧义）。
 */
public class JdbcRunUsageStoreTest {

    /** 用于 save 验证的 Mock JdbcTemplate（Mockito + varargs 这里安全） */
    private JdbcTemplate mockJdbcForSave;
    /** 用于 findByDate 的可记录 JdbcTemplate 子类（绕开 Mockito varargs） */
    private RecordingJdbcTemplate recordingJdbc;

    @Before
    public void setUp() {
        mockJdbcForSave = mock(JdbcTemplate.class);
        recordingJdbc = new RecordingJdbcTemplate();
    }

    /** JdbcTemplate 子类：拦截 queryForList(String, Object[]) 记录 sql/args，返回预设行 */
    private static class RecordingJdbcTemplate extends JdbcTemplate {
        String lastSql;
        Object[] lastArgs;
        List<Map<String, Object>> toReturn = new ArrayList<>();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            this.lastSql = sql;
            this.lastArgs = args;
            return toReturn;
        }
    }

    // ============================= save 测试 =============================

    @Test
    public void saveInsertsRunUsageRow() {
        JdbcRunUsageStore store = new JdbcRunUsageStore(mockJdbcForSave);
        RunUsage u = usage("t1", "u1", "s1", true);
        store.save(u);
        // INSERT SQL 现在含 tenant_id、user_id 两列（共 12 个占位符）
        verify(mockJdbcForSave).update(contains("INSERT INTO claw_run_usage"),
                eq("t1"), eq("u1"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ============================= findByDate 测试 =============================

    @Test
    public void findByDateMapsColumnsToDisplayFields_andFiltersByScope() {
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
        recordingJdbc.toReturn = new ArrayList<>(Arrays.asList(row));

        JdbcRunUsageStore store = new JdbcRunUsageStore(recordingJdbc);
        AgentScope scope = AgentScope.of("t1", "u1");
        List<Map<String, Object>> runs = store.findByDate(scope, "2099-01-01");
        assertEquals(1, runs.size());
        Map<String, Object> e = runs.get(0);
        assertEquals("s1", e.get("sessionId"));
        assertEquals("deepseek-chat", e.get("model"));
        assertEquals(Boolean.TRUE, e.get("success"));
        assertEquals(1234L, e.get("durationMs"));
        assertEquals(3, e.get("steps"));
        assertTrue("ts 应为展示字段", e.containsKey("ts"));

        // 1) SQL 证据：显式包含 tenant_id=? AND user_id=?
        assertNotNull("JdbcTemplate.queryForList 必须被调用", recordingJdbc.lastSql);
        String sql = recordingJdbc.lastSql.toLowerCase();
        int tenantIdx = sql.indexOf("tenant_id = ?");
        int userIdx = sql.indexOf("user_id = ?");
        assertTrue("SQL 应显式包含 tenant_id 条件，实现 scope 隔离", tenantIdx >= 0);
        assertTrue("SQL 应显式包含 user_id 条件，实现 scope 隔离", userIdx >= 0);
        int timeIdx = sql.indexOf("create_time >=");
        assertTrue("scope 过滤必须出现在时间窗口之前", tenantIdx < timeIdx);
        assertTrue("scope 过滤必须出现在时间窗口之前", userIdx < timeIdx);

        // 2) 参数证据：[0]=tenantId("t1") / [1]=userId("u1") / [2..3]=时间窗口
        Object[] args = recordingJdbc.lastArgs;
        assertNotNull("参数数组不能为空", args);
        assertTrue("参数长度 >= 4（tenant/user/winStart/winEnd）", args.length >= 4);
        assertEquals("t1", args[0]);
        assertEquals("u1", args[1]);
    }

    @Test
    public void defaultScope_filtersEmptyTenantAndUser() {
        JdbcRunUsageStore store = new JdbcRunUsageStore(recordingJdbc);
        List<Map<String, Object>> runs = store.findByDate(null, "2099-01-01");
        assertTrue(runs.isEmpty());

        // 默认 scope 下前两参数是 "" / ""（空串表示默认空间，对齐 AgentScope.defaultScope）
        Object[] args = recordingJdbc.lastArgs;
        assertNotNull("参数数组不能为空", args);
        assertTrue("参数长度 >= 4", args.length >= 4);
        assertEquals("默认 scope 下 tenant 应为空串", "", args[0]);
        assertEquals("默认 scope 下 user 应为空串", "", args[1]);
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
}
