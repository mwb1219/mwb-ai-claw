package com.mwb.ai.claw.infrastructure.collaboration.delegate.run;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.util.JsonUtils;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;

/**
 * H1-P1 切片 2：本地文件版编排运行记录存储（{@code store=file}）测试。
 * 覆盖：create/get/update 字段读写、重启（新实例同目录）载入、claimForResume 认领、
 * listGated/findGated、deleteStale 悬挂清理与 scope 隔离。
 */
public class LocalFileOrchestrationRunStoreTest {

    private Path dir;
    private LocalFileOrchestrationRunStore store;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("claw-orchestration-run-test");
        store = newStore(dir);
    }

    private LocalFileOrchestrationRunStore newStore(Path root) {
        AgentProperties properties = new AgentProperties();
        properties.getCollaboration().getOrchestrationRun().setStore("file");
        properties.getCollaboration().getOrchestrationRun().setDir(root.toString());
        return new LocalFileOrchestrationRunStore(properties);
    }

    private OrchestrationRun run(String id, String tenant, String userId) {
        OrchestrationRun run = new OrchestrationRun();
        run.setRunId(id);
        run.setTenantId(tenant);
        run.setUserId(userId);
        run.setSessionId("test-session");
        run.setOrchestrationId("todo-delegate");
        run.setPhase(OrchestrationRun.PHASE_RUNNING);
        run.setTask("根任务");
        run.setPlannerAgentId("architect");
        run.setStackJson("[]");
        store.create(run);
        return run;
    }

    @Test
    public void createAndGet_roundTripPreservesFields() {
        OrchestrationRun run = run("r1", "t1", "u1");
        run.setReply("最终答复");
        run.setPhase(OrchestrationRun.PHASE_DONE);
        run.setStackJson("[{\"step\":\"WAVE\"}]");
        store.update(run);

        OrchestrationRun loaded = store.get(scope("t1", "u1"), "r1").orElseThrow(AssertionError::new);
        assertEquals(OrchestrationRun.PHASE_DONE, loaded.getPhase());
        assertEquals("最终答复", loaded.getReply());
        assertEquals("[{\"step\":\"WAVE\"}]", loaded.getStackJson());
        assertTrue("create 时应自动写入 createTime/updateTime", loaded.getCreateTime() > 0);
    }

    @Test
    public void update_incrementsVersionAndUpdateTime() throws Exception {
        OrchestrationRun run = run("r2", "t1", "u1");
        long v0 = runOf("r2").getVersion();
        long t0 = runOf("r2").getUpdateTime();

        Thread.sleep(5);
        store.update(run);
        OrchestrationRun after = runOf("r2");
        assertEquals("update 应自增乐观锁版本", v0 + 1, after.getVersion());
        assertTrue("update 应刷新 updateTime", after.getUpdateTime() > t0);
    }

    @Test
    public void persistsAcrossRestart_newInstanceReloads() throws Exception {
        run("r3", "t1", "u1");
        // 模拟实例重启：同一目录新建 Store → 数据仍在
        LocalFileOrchestrationRunStore fresh = newStore(dir);
        OrchestrationRun loaded = fresh.get(scope("t1", "u1"), "r3").orElseThrow(AssertionError::new);
        assertEquals("重启后应能从文件载入运行记录", "r3", loaded.getRunId());
        assertEquals(OrchestrationRun.PHASE_RUNNING, loaded.getPhase());

        String fileName = dir.resolve("t1__u1__r3.json").toString();
        assertTrue("运行记录应以 JSON 文件落盘", Files.isRegularFile(Paths.get(fileName)));
    }

    @Test
    public void claimForResume_gatesAndDoneBehave() {
        OrchestrationRun gated = run("r4", "t1", "u1");
        gated.setPhase(OrchestrationRun.PHASE_GATE);
        store.update(gated);
        assertTrue("GATE 运行记录应可认领", store.claimForResume(scope("t1", "u1"), "r4"));
        assertEquals("认领后相位应置 RUNNING", OrchestrationRun.PHASE_RUNNING, runOf("r4").getPhase());

        OrchestrationRun done = run("r5", "t1", "u1");
        done.setPhase(OrchestrationRun.PHASE_DONE);
        store.update(done);
        assertFalse("DONE 记录不应可认领", store.claimForResume(scope("t1", "u1"), "r5"));
    }

    @Test
    public void listGated_and_findGated_onlyPendingGates() {
        OrchestrationRun gated = run("g1", "t1", "u1");
        gated.setPhase(OrchestrationRun.PHASE_GATE);
        gated.setGateLayer("root");
        store.update(gated);
        OrchestrationRun decided = run("g2", "t1", "u1");
        decided.setPhase(OrchestrationRun.PHASE_GATE);
        decided.setGateLayer("root");
        decided.setGateDecision(OrchestrationRun.DECISION_APPROVED);
        store.update(decided);
        // 非本 scope 的挂起记录
        OrchestrationRun other = run("g3", "t2", "u2");
        other.setPhase(OrchestrationRun.PHASE_GATE);
        other.setGateLayer("root");
        store.update(other);

        List<OrchestrationRun> pending = store.listGated(scope("t1", "u1"), "test-session");
        assertEquals("仅列出本 scope 且决策未决的 GATE 记录", 1, pending.size());
        assertEquals("g1", pending.get(0).getRunId());

        String found = store.findGated(scope("t1", "u1"), "test-session", "root")
                .map(OrchestrationRun::getRunId).orElse(null);
        assertEquals("findGated 按层定位待审批记录", "g1", found);
    }

    @Test
    public void deleteStale_removesOnlyExpiredPending_andScopeIsolation() throws Exception {
        writeStale("s1", "t1", "u1", OrchestrationRun.PHASE_GATE, oldTime());
        writeStale("s2", "t1", "u1", OrchestrationRun.PHASE_SUSPENDED, oldTime());
        // 未过期 LATE 挂起与过期 DONE：均应保留
        writeStale("s3", "t1", "u1", OrchestrationRun.PHASE_GATE, System.currentTimeMillis());
        writeStale("s4", "t1", "u1", OrchestrationRun.PHASE_DONE, oldTime());

        int removed = store.deleteStale(System.currentTimeMillis() - 1_000_000L, 10);

        assertEquals("仅清除过期且 phase 仍为 GATE/SUSPENDED/RUNNING 的记录", 2, removed);
        assertNull("过期 GATE 应被清除", store.get(scope("t1", "u1"), "s1").orElse(null));
        assertNull("过期 SUSPENDED 应被清除", store.get(scope("t1", "u1"), "s2").orElse(null));
        assertTrue("未过期 GATE 应保留", store.get(scope("t1", "u1"), "s3").isPresent());
        assertTrue("DONE 记录应保留", store.get(scope("t1", "u1"), "s4").isPresent());
    }

    @Test
    public void delete_removesFile() {
        run("d1", "t1", "u1");
        store.delete(scope("t1", "u1"), "d1");
        assertTrue("删除后记录不应存在", !store.get(scope("t1", "u1"), "d1").isPresent());
    }

    // ---------------- 辅助 ----------------

    private AgentScope scope(String tenant, String userId) {
        return AgentScope.of(tenant, userId);
    }

    private OrchestrationRun runOf(String runId) {
        return store.get(scope("t1", "u1"), runId).orElseThrow(AssertionError::new);
    }

    /** 直接把带自定义 updateTime 的 run 落盘（绕过 create 的 now 时间戳），文件名与 Store 命名一致 */
    private void writeStale(String id, String tenant, String userId, String phase, long updateTime) throws Exception {
        OrchestrationRun run = new OrchestrationRun();
        run.setRunId(id);
        run.setTenantId(tenant);
        run.setUserId(userId);
        run.setSessionId("test-session");
        run.setOrchestrationId("todo-delegate");
        run.setPhase(phase);
        run.setTask("根任务");
        run.setPlannerAgentId("architect");
        run.setUpdateTime(updateTime);
        run.setCreateTime(updateTime);
        Files.write(dir.resolve(tenant + "__" + userId + "__" + id + ".json"),
                JsonUtils.toJson(run).getBytes(StandardCharsets.UTF_8));
    }

    private long oldTime() {
        return System.currentTimeMillis() - 100_000_000L;
    }
}