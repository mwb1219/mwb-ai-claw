package com.mwb.ai.claw.infrastructure.collaboration.approval;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.collaboration.model.TodoDefinition;

/**
 * 审批门禁单元测试（T8）：
 * 等待超时自动降级为 TIMEOUT、人工 approve / reject 唤醒阻塞线程、
 * 注册表按租户 / 用户维度隔离、跨租户越权审批不生效。
 */
public class ApprovalRegistryTest {

    private static final List<TodoDefinition> EMPTY_PLAN = Collections.emptyList();

    // ==================== PendingApproval：超时降级语义 ====================

    @Test
    public void await_timeout_returnsTimeoutAndMarksDecided() {
        PendingApproval pa = pending("s1", "root");
        long start = System.currentTimeMillis();
        ApprovalDecision decision = pa.await(30);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("等待超时应降级为 TIMEOUT", ApprovalDecision.TIMEOUT, decision);
        assertTrue("超时后节点应标记已决策", pa.isDecided());
        assertTrue("不应在超时前返回", elapsed >= 25);
    }

    @Test
    public void await_zeroBlocksUntilDecided() throws Exception {
        PendingApproval pa = pending("s1", "root");
        AtomicReference<ApprovalDecision> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(pa.await(0))); // <=0 无限等待
        t.start();
        Thread.sleep(50);
        assertFalse("未决策前应持续阻塞", pa.isDecided());
        pa.decide(ApprovalDecision.APPROVED);
        t.join(1000);
        assertFalse("决策后应唤醒阻塞线程", t.isAlive());
        assertEquals(ApprovalDecision.APPROVED, result.get());
    }

    // ==================== ApprovalRegistry：决策链路与生命周期 ====================

    @Test
    public void approve_wakesAwaitingThreadAndRemovesNode() throws Exception {
        ApprovalRegistry registry = new ApprovalRegistry();
        AgentScope scope = AgentScope.of("tenant-a", "u1");
        PendingApproval pa = registry.register(scope, "s1", "root", "task-a", EMPTY_PLAN);
        CountDownLatch decided = new CountDownLatch(1);
        AtomicReference<ApprovalDecision> result = new AtomicReference<>();
        Thread t = new Thread(() -> {
            result.set(pa.await(5000));
            decided.countDown();
        });
        t.start();
        Thread.sleep(50);

        assertTrue("审批通过应命中待审批节点", registry.approve(scope, "s1", "root"));
        assertTrue("决策后应唤醒阻塞线程", decided.await(2, TimeUnit.SECONDS));
        assertEquals(ApprovalDecision.APPROVED, result.get());
        t.join(1000);
        assertTrue("节点已决策应从注册表移除", registry.listPending(scope, null).isEmpty());
    }

    @Test
    public void reject_wakesAwaitingThread() throws Exception {
        ApprovalRegistry registry = new ApprovalRegistry();
        AgentScope scope = AgentScope.of("tenant-a", "u1");
        PendingApproval pa = registry.register(scope, "s1", "root", "task-a", EMPTY_PLAN);
        AtomicReference<ApprovalDecision> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(pa.await(5000)));
        t.start();
        Thread.sleep(50);

        assertTrue("审批拒绝应命中待审批节点", registry.reject(scope, "s1", "root"));
        t.join(1000);
        assertEquals(ApprovalDecision.REJECTED, result.get());
    }

    @Test
    public void timeout_thenNodeStillListedUntilExplicitlyRemoved() throws Exception {
        ApprovalRegistry registry = new ApprovalRegistry();
        AgentScope scope = AgentScope.of("tenant-a", "u1");
        PendingApproval pa = registry.register(scope, "s1", "root", "task-a", EMPTY_PLAN);
        // 编排线程等待超时
        assertEquals(ApprovalDecision.TIMEOUT, pa.await(20));

        // 超时仅决定节点命运，注册表仍残留（需编排线程显式 remove 清理，避免重复决策误判）
        assertFalse(registry.listPending(scope, null).isEmpty());
        registry.remove(pa);
        assertTrue("显式移除后列表应为空", registry.listPending(scope, null).isEmpty());
    }

    // ==================== 多租户隔离 ====================

    @Test
    public void listPending_isolatesByNamespace() {
        ApprovalRegistry registry = new ApprovalRegistry();
        registry.register(AgentScope.of("tenant-a", "u1"), "s1", "root", "task-a", EMPTY_PLAN);
        registry.register(AgentScope.of("tenant-b", "u1"), "s1", "root", "task-b", EMPTY_PLAN);

        List<PendingApproval> onlyA = registry.listPending(AgentScope.of("tenant-a", "u1"), null);
        assertEquals(1, onlyA.size());
        assertEquals("task-a", onlyA.get(0).getTask());

        List<PendingApproval> onlyB = registry.listPending(AgentScope.of("tenant-b", "u1"), null);
        assertEquals(1, onlyB.size());
        assertEquals("task-b", onlyB.get(0).getTask());
    }

    @Test
    public void approveByOtherTenant_doesNotAffectOtherNodes() {
        ApprovalRegistry registry = new ApprovalRegistry();
        registry.register(AgentScope.of("tenant-a", "u1"), "s1", "root", "task-a", EMPTY_PLAN);
        registry.register(AgentScope.of("tenant-b", "u1"), "s1", "root", "task-b", EMPTY_PLAN);

        // 用 tenant-a 审批自己的节点
        assertTrue(registry.approve(AgentScope.of("tenant-a", "u1"), "s1", "root"));
        assertTrue("tenant-a 节点应已移除",
                registry.listPending(AgentScope.of("tenant-a", "u1"), null).isEmpty());

        List<PendingApproval> b = registry.listPending(AgentScope.of("tenant-b", "u1"), null);
        assertEquals("tenant-b 的节点不应被跨租户审批影响", 1, b.size());
        assertEquals("task-b", b.get(0).getTask());
    }

    private PendingApproval pending(String sessionId, String layerKey) {
        return new PendingApproval(AgentScope.of("tenant-a", "u1"), sessionId, layerKey,
                "task", EMPTY_PLAN);
    }
}