package com.mwb.ai.claw.example.web.memory.synthesis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.common.message.MessageQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mwb.ai.claw.domain.core.MessageRole;
import com.mwb.ai.claw.domain.memory.layered.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.layered.model.MemoryPage;
import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesisDispatcher;
import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesisDispatcher.Kind;
import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesisDispatcher.SynthesisEvent;
import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesizer;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.example.web.memory.synthesis.RocketMqMemorySynthesisDispatcher.MemoryPageStoreAccessor;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;
import com.mwb.ai.claw.domain.util.JsonUtils;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * RocketMqMemorySynthesisDispatcher 单元测试（Phase 3）。
 * <p>
 * 覆盖 produce / consume 主流程的正常路径与异常兜底：
 * <ul>
 *   <li>T1  空快照跳过（staging/sendOneway 均不调用）</li>
 *   <li>T2  snapshotSupplier 返回 null 也跳过</li>
 *   <li>T3  正常 AFTER_TURN（TAG_AFTER_TURN + sessionId hash 分区）</li>
 *   <li>T4  正常 AFTER_SESSION（TAG_AFTER_SESSION）</li>
 *   <li>T5  producer.sendOneway 异常不抛（吞掉 + 记 metrics）</li>
 *   <li>T6  tenantId/userId null 时 safe() 兜底为空串</li>
 *   <li>T7  正常 AFTER_TURN consume（staging.load → claimSummaryBlock → summarizeBlock → saveSummary → staging.delete）</li>
 *   <li>T8  正常 AFTER_SESSION consume（claimArchiveBlock + extractFacts + upsertFactAtomic）</li>
 *   <li>T9  staging.load 返回 null（过期清理）→ 直接 return</li>
 *   <li>T10 handler 异常仍 staging.delete（finally 保证清理）</li>
 *   <li>T11 staging.delete 自身异常不阻断</li>
 *   <li>T12 pendingCount 返回 0（MQ 堆积靠 Broker ConsumeLag）</li>
 *   <li>T13 SELECTOR 分区确定性（同 sessionId → 同 queue）</li>
 * </ul>
 * <p>
 * 注：不 import {@code org.apache.rocketmq.common.message.Message}（与 domain 层的 Message 同名冲突），
 * 所有 RocketMQ Message 使用全限定名。
 */
@ExtendWith(MockitoExtension.class)
class RocketMqMemorySynthesisDispatcherTest {

    @Mock
    DefaultMQProducer producer;
    @Mock
    SnapshotStaging staging;
    @Mock
    MemorySynthesizer synthesizer;
    @Mock
    MemoryPageStoreAccessor pageStoreAccessor;

    @Captor
    ArgumentCaptor<org.apache.rocketmq.common.message.Message> mqMsgCaptor;
    @Captor
    ArgumentCaptor<MessageQueueSelector> selectorCaptor;

    private MetricsRecorder metrics;
    private LayeredMemoryConfig config;
    private RocketMqMemorySynthesisDispatcher dispatcher;

    private static final AgentScope SCOPE = AgentScope.of("tenantA", "user1");
    private static final String SESSION_ID = "sess-001";

    @BeforeEach
    void setUp() {
        metrics = new MetricsRecorder(new SimpleMeterRegistry());
        config = new LayeredMemoryConfig();
        config.setSummaryBlockSize(5);
        config.setArchiveEnabled(true);
        config.setImportanceThreshold(0.5);
        dispatcher = new RocketMqMemorySynthesisDispatcher(
                producer, staging, config, synthesizer, metrics, pageStoreAccessor);
    }

    // ==================== 辅助 ====================

    private SynthesisEvent newEvent(Kind kind, List<com.mwb.ai.claw.domain.core.Message> snapshot) {
        return new SynthesisEvent(
                SCOPE, SESSION_ID, kind,
                snapshot == null ? null : () -> snapshot,
                null);
    }

    private List<com.mwb.ai.claw.domain.core.Message> sampleMessages(int count) {
        List<com.mwb.ai.claw.domain.core.Message> msgs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            msgs.add(com.mwb.ai.claw.domain.core.Message.of(
                    i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT, "msg-" + i));
        }
        return msgs;
    }

    // ==================== produce 测试 ====================

    @Test
    @DisplayName("T1 空快照跳过，staging/sendOneway 均不调用")
    void t1_emptySnapshot_skip() throws Exception {
        SynthesisEvent ev = newEvent(Kind.AFTER_TURN, Collections.emptyList());
        dispatcher.produce(ev);

        verify(staging, never()).save(any(), anyString(), any(), anyList());
        verify(producer, never()).sendOneway(
                any(org.apache.rocketmq.common.message.Message.class), any(MessageQueueSelector.class), any());
    }

    @Test
    @DisplayName("T2 snapshotSupplier 返回 null 也跳过")
    void t2_nullSnapshotSupplier_skip() throws Exception {
        SynthesisEvent ev = new SynthesisEvent(SCOPE, SESSION_ID, Kind.AFTER_TURN, () -> null, null);
        dispatcher.produce(ev);

        verify(staging, never()).save(any(), anyString(), any(), anyList());
        verify(producer, never()).sendOneway(
                any(org.apache.rocketmq.common.message.Message.class), any(MessageQueueSelector.class), any());
    }

    @Test
    @DisplayName("T3 正常 AFTER_TURN：staging.save → sendOneway，TAG=AFTER_TURN，keys=sessionId")
    void t3_afterTurn_produceSuccess() throws Exception {
        List<com.mwb.ai.claw.domain.core.Message> snapshot = sampleMessages(8);
        when(staging.save(eq(SCOPE), eq(SESSION_ID), eq(Kind.AFTER_TURN), eq(snapshot)))
                .thenReturn(1710000000000L);

        dispatcher.produce(newEvent(Kind.AFTER_TURN, snapshot));

        verify(staging).save(SCOPE, SESSION_ID, Kind.AFTER_TURN, snapshot);
        verify(producer).sendOneway(mqMsgCaptor.capture(), selectorCaptor.capture(), eq(SESSION_ID));

        org.apache.rocketmq.common.message.Message mqMsg = mqMsgCaptor.getValue();
        assertEquals(RocketMqMemorySynthesisDispatcher.TOPIC, mqMsg.getTopic());
        assertEquals(RocketMqMemorySynthesisDispatcher.TAG_AFTER_TURN, mqMsg.getTags());
        assertEquals(SESSION_ID, mqMsg.getKeys());

        SynthTaskMessage parsed = JsonUtils.fromJson(
                new String(mqMsg.getBody(), StandardCharsets.UTF_8), SynthTaskMessage.class);
        assertEquals(SCOPE.getTenantId(), parsed.getTenantId());
        assertEquals(SCOPE.getUserId(), parsed.getUserId());
        assertEquals(SESSION_ID, parsed.getSessionId());
        assertEquals("AFTER_TURN", parsed.getKind());
        assertEquals(1710000000000L, parsed.getSnapshotVersion());
    }

    @Test
    @DisplayName("T4 正常 AFTER_SESSION：TAG=AFTER_SESSION")
    void t4_afterSession_tag() throws Exception {
        List<com.mwb.ai.claw.domain.core.Message> snapshot = sampleMessages(10);
        when(staging.save(any(), anyString(), eq(Kind.AFTER_SESSION), anyList())).thenReturn(42L);

        dispatcher.produce(newEvent(Kind.AFTER_SESSION, snapshot));

        verify(producer).sendOneway(mqMsgCaptor.capture(), any(), any());
        assertEquals(RocketMqMemorySynthesisDispatcher.TAG_AFTER_SESSION, mqMsgCaptor.getValue().getTags());
    }

    @Test
    @DisplayName("T5 producer.sendOneway 异常不抛出（吞掉 + 记 metric）")
    void t5_producerFail_silent() throws Exception {
        List<com.mwb.ai.claw.domain.core.Message> snapshot = sampleMessages(3);
        when(staging.save(any(), anyString(), any(), anyList())).thenReturn(1L);
        doThrow(new RuntimeException("broker unreachable")).when(producer)
                .sendOneway(any(org.apache.rocketmq.common.message.Message.class),
                        any(MessageQueueSelector.class), any());

        assertDoesNotThrow(() -> dispatcher.produce(newEvent(Kind.AFTER_TURN, snapshot)));
    }

    @Test
    @DisplayName("T6 tenantId/userId null 时 safe() 兜底为空串")
    void t6_nullScopedValues_safe() throws Exception {
        AgentScope nullScope = AgentScope.of(null, null);
        List<com.mwb.ai.claw.domain.core.Message> snapshot = sampleMessages(2);
        when(staging.save(any(), anyString(), any(), anyList())).thenReturn(99L);

        SynthesisEvent ev = new SynthesisEvent(nullScope, SESSION_ID, Kind.AFTER_TURN, () -> snapshot, null);
        dispatcher.produce(ev);

        verify(producer).sendOneway(mqMsgCaptor.capture(), any(), any());
        SynthTaskMessage parsed = JsonUtils.fromJson(
                new String(mqMsgCaptor.getValue().getBody(), StandardCharsets.UTF_8), SynthTaskMessage.class);
        assertEquals("", parsed.getTenantId());
        assertEquals("", parsed.getUserId());
    }

    // ==================== consume 测试 ====================

    @Test
    @DisplayName("T7 AFTER_TURN：staging.load → claimSummaryBlock → summarizeBlock → saveSummary → staging.delete")
    void t7_afterTurn_flow() {
        List<com.mwb.ai.claw.domain.core.Message> snapshot = sampleMessages(12);
        long version = 1710000000000L;

        when(staging.load(eq(SCOPE), eq(SESSION_ID), eq(Kind.AFTER_TURN), eq(version))).thenReturn(snapshot);
        // 12 条消息 blockSize=5 → claim 三次：[0,5), [5,10), [10,12)，第三次返回 -1
        when(pageStoreAccessor.claimSummaryBlock(any(), eq(SESSION_ID), eq(0), eq(5), eq(12)))
                .thenReturn(0).thenReturn(5).thenReturn(-1);
        when(synthesizer.summarizeBlock(any(), anyList())).thenReturn("summary-text");

        SynthesisEvent ev = new SynthesisEvent(SCOPE, SESSION_ID, Kind.AFTER_TURN, null, null);
        ev.stagingVersion = version;
        dispatcher.consume(ev);

        verify(staging).delete(SCOPE, SESSION_ID, Kind.AFTER_TURN, version);
        verify(synthesizer, times(2)).summarizeBlock(eq(SCOPE), anyList());
        verify(pageStoreAccessor, times(2)).saveSummary(eq(SCOPE), any(MemoryPage.class));
    }

    @Test
    @DisplayName("T8 AFTER_SESSION：claimArchiveBlock + extractFacts + upsertFactAtomic")
    void t8_afterSession_flow() {
        List<com.mwb.ai.claw.domain.core.Message> snapshot = sampleMessages(8);
        long version = 999L;

        when(staging.load(any(), anyString(), eq(Kind.AFTER_SESSION), anyLong())).thenReturn(snapshot);
        when(pageStoreAccessor.claimArchiveBlock(any(), anyString(), eq(0), eq(5), eq(8)))
                .thenReturn(0).thenReturn(5).thenReturn(-1);

        MemoryPage factA = MemoryPage.fact("key-a", "fact-A", 0.8, SESSION_ID);
        MemoryPage factB = MemoryPage.fact("key-b", "fact-B", 0.9, SESSION_ID);
        MemoryPage factLow = MemoryPage.fact("key-low", "low", 0.3, SESSION_ID);
        when(synthesizer.extractFacts(any(), anyList()))
                .thenReturn(Arrays.asList(factA, factB, factLow));
        when(pageStoreAccessor.loadFacts(any())).thenReturn(Collections.emptyList());
        when(synthesizer.mergeFact(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        SynthesisEvent ev = new SynthesisEvent(SCOPE, SESSION_ID, Kind.AFTER_SESSION, null, null);
        ev.stagingVersion = version;
        dispatcher.consume(ev);

        verify(pageStoreAccessor, times(2)).saveArchive(eq(SCOPE), any(MemoryPage.class));
        // factLow importance=0.3 < 0.5 阈值，只有 factA/factB 被 upsert
        verify(pageStoreAccessor, times(2)).upsertFactAtomic(eq(SCOPE), any(MemoryPage.class));
        verify(staging).delete(SCOPE, SESSION_ID, Kind.AFTER_SESSION, version);
    }

    @Test
    @DisplayName("T9 staging.load 返回 null（过期清理）→ 直接 return，finally 幂等 delete 仍调用")
    void t9_stagingNull_returnSilently() {
        when(staging.load(any(), anyString(), any(), anyLong())).thenReturn(null);

        SynthesisEvent ev = new SynthesisEvent(SCOPE, SESSION_ID, Kind.AFTER_TURN, null, null);
        ev.stagingVersion = 123L;

        assertDoesNotThrow(() -> dispatcher.consume(ev));
        verify(synthesizer, never()).summarizeBlock(any(), anyList());
        verify(synthesizer, never()).extractFacts(any(), anyList());
        verify(staging).delete(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("T10 handler 内部异常 → 仍 staging.delete（finally 保证清理）")
    void t10_handlerException_cleanup() {
        List<com.mwb.ai.claw.domain.core.Message> snapshot = sampleMessages(5);
        long version = 10L;

        when(staging.load(any(), anyString(), any(), anyLong())).thenReturn(snapshot);
        when(pageStoreAccessor.claimSummaryBlock(any(), anyString(), eq(0), anyInt(), anyInt())).thenReturn(0);
        when(synthesizer.summarizeBlock(any(), anyList())).thenThrow(new RuntimeException("LLM timeout"));

        SynthesisEvent ev = new SynthesisEvent(SCOPE, SESSION_ID, Kind.AFTER_TURN, null, null);
        ev.stagingVersion = version;

        assertDoesNotThrow(() -> dispatcher.consume(ev));
        verify(staging).delete(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("T11 staging.delete 自身抛异常不阻断主流程（finally 内部 try-catch）")
    void t11_stagingDeleteException_swallowed() {
        List<com.mwb.ai.claw.domain.core.Message> snapshot = sampleMessages(3);
        long version = 7L;

        when(staging.load(any(), anyString(), any(), anyLong())).thenReturn(snapshot);
        when(pageStoreAccessor.claimSummaryBlock(any(), anyString(), eq(0), anyInt(), anyInt()))
                .thenReturn(0).thenReturn(-1);
        when(synthesizer.summarizeBlock(any(), anyList())).thenReturn("ok");
        doThrow(new RuntimeException("DB gone")).when(staging).delete(any(), anyString(), any(), anyLong());

        SynthesisEvent ev = new SynthesisEvent(SCOPE, SESSION_ID, Kind.AFTER_TURN, null, null);
        ev.stagingVersion = version;

        assertDoesNotThrow(() -> dispatcher.consume(ev));
    }

    // ==================== pendingCount / SELECTOR ====================

    @Test
    @DisplayName("T12 pendingCount 返回 0（MQ 堆积靠 Broker ConsumeLag 查询）")
    void t12_pendingCount() {
        assertEquals(0, dispatcher.pendingCount());
    }

    @Test
    @DisplayName("T13 SELECTOR 分区确定性：同 sessionId → 同 queue；不同/空 sessionId 不崩")
    void t13_selectorDeterminism() throws Exception {
        List<com.mwb.ai.claw.domain.core.Message> snapshot = sampleMessages(2);
        when(staging.save(any(), anyString(), any(), anyList())).thenReturn(1L);

        dispatcher.produce(new SynthesisEvent(SCOPE, "same-session", Kind.AFTER_TURN, () -> snapshot, null));
        verify(producer).sendOneway(mqMsgCaptor.capture(), selectorCaptor.capture(), eq("same-session"));

        // 构造 8 个 queue 的模拟列表
        List<MessageQueue> queues = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            queues.add(new MessageQueue("CLAW_SYNTH_TASK", "broker-a", i));
        }

        MessageQueueSelector selector = selectorCaptor.getValue();

        // 同 sessionId → 同 queueIndex
        int q1 = selector.select(queues, null, "same-session").getQueueId();
        int q2 = selector.select(queues, null, "same-session").getQueueId();
        assertEquals(q1, q2, "同 sessionId 必须 hash 到同一个 queue");

        // 不同 sessionId → 不崩
        int q3 = selector.select(queues, null, "another-session").getQueueId();
        assertTrue(q3 >= 0 && q3 < queues.size());

        // 空 sessionId → 不崩
        int q4 = selector.select(queues, null, "").getQueueId();
        assertTrue(q4 >= 0 && q4 < queues.size());
    }
}
