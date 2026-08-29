package com.mwb.ai.claw.example.web.memory.synthesis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;

import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesisDispatcher;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesisDispatcher.Kind;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * RocketMqSynthesisConsumer 单元测试（Phase 3 MQ 消费端）：
 * <ul>
 *   <li>C1  AFTER_TURN 正常：反序列化 → 调 dispatcher.consume(event.stagingVersion=version)</li>
 *   <li>C2  AFTER_SESSION 正常</li>
 *   <li>C3  非法 JSON → 不调 consume，直接 ACK 丢弃</li>
 *   <li>C4  kind 非法（UNKNOWN_KIND）→ 不调 consume</li>
 *   <li>C5  body 为空字符串 → 不抛异常，不调 consume</li>
 *   <li>C6  dispatcher.consume 抛异常 → consumer 向上传播（RocketMQ 重试接管，最多 3 次后进 DLQ）</li>
 *   <li>C7  消费延迟 > 60s 仍正常调用 consume（只打 warn 日志）</li>
 *   <li>C8  SynthTaskMessage JSON round-trip：全字段保真</li>
 *   <li>C9  SynthTaskMessage 全参构造自动填 produceTime</li>
 *   <li>C10 SynthTaskMessage 无参构造默认值 + setter 覆盖</li>
 *   <li>C11 SynthTaskMessage toString 包含关键字段</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RocketMqSynthesisConsumerTest {

    @Mock
    RocketMqMemorySynthesisDispatcher dispatcher;

    @Captor
    ArgumentCaptor<MemorySynthesisDispatcher.SynthesisEvent> evCaptor;

    private RocketMqSynthesisConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new RocketMqSynthesisConsumer(dispatcher);
    }

    // ==================== 辅助 ====================

    private MessageExt buildMessage(SynthTaskMessage body) {
        MessageExt msg = new MessageExt();
        msg.setTopic(RocketMqMemorySynthesisDispatcher.TOPIC);
        msg.setBody(JsonUtils.toJson(body).getBytes(StandardCharsets.UTF_8));
        return msg;
    }

    private MessageExt buildRawMessage(String json) {
        MessageExt msg = new MessageExt();
        msg.setTopic(RocketMqMemorySynthesisDispatcher.TOPIC);
        msg.setBody(json.getBytes(StandardCharsets.UTF_8));
        return msg;
    }

    // ==================== onMessage 测试 ====================

    @Test
    @DisplayName("C1 AFTER_TURN 正常反序列化 → dispatcher.consume(event.stagingVersion=version)")
    void c1_afterTurn_normal() {
        SynthTaskMessage body = new SynthTaskMessage(
                "tenantX", "userY", "sess-001", "AFTER_TURN", 1710000000000L);
        body.setProduceTime(System.currentTimeMillis() - 5000);

        consumer.onMessage(buildMessage(body));

        verify(dispatcher).consume(evCaptor.capture());
        MemorySynthesisDispatcher.SynthesisEvent ev = evCaptor.getValue();
        assertEquals(Kind.AFTER_TURN, ev.kind);
        assertEquals("sess-001", ev.sessionId);
        assertEquals(1710000000000L, ev.stagingVersion);
        assertEquals("tenantX", ev.scope.getTenantId());
        assertEquals("userY", ev.scope.getUserId());
    }

    @Test
    @DisplayName("C2 AFTER_SESSION 正常反序列化")
    void c2_afterSession_normal() {
        SynthTaskMessage body = new SynthTaskMessage("t", "u", "sess-99", "AFTER_SESSION", 42L);

        consumer.onMessage(buildMessage(body));

        verify(dispatcher).consume(evCaptor.capture());
        assertEquals(Kind.AFTER_SESSION, evCaptor.getValue().kind);
        assertEquals(42L, evCaptor.getValue().stagingVersion);
    }

    @Test
    @DisplayName("C3 非法 JSON → 不调 consume，直接 ACK 丢弃")
    void c3_invalidJson_discard() {
        consumer.onMessage(buildRawMessage("this is not json {{{"));
        verify(dispatcher, never()).consume(any());
    }

    @Test
    @DisplayName("C4 kind 非法（UNKNOWN_KIND）→ 不调 consume")
    void c4_unknownKind_discard() {
        SynthTaskMessage body = new SynthTaskMessage("t", "u", "s", "UNKNOWN_KIND", 1L);
        consumer.onMessage(buildMessage(body));
        verify(dispatcher, never()).consume(any());
    }

    @Test
    @DisplayName("C5 body 为空字符串 → 不抛异常，不调 consume")
    void c5_emptyBody_noThrow() {
        assertDoesNotThrow(() -> consumer.onMessage(buildRawMessage("")));
        verify(dispatcher, never()).consume(any());
    }

    @Test
    @DisplayName("C6 dispatcher.consume 抛异常 → consumer 向上传播（RocketMQ 重试接管）")
    void c6_dispatcherException_propagates() {
        SynthTaskMessage body = new SynthTaskMessage("t", "u", "s", "AFTER_TURN", 1L);
        doThrow(new RuntimeException("staging DB down")).when(dispatcher).consume(any());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> consumer.onMessage(buildMessage(body)));
        assertEquals("staging DB down", ex.getMessage());
        verify(dispatcher).consume(any());
    }

    @Test
    @DisplayName("C7 消费延迟 > 60s 仍正常调用 consume")
    void c7_highLatency_stillConsumes() {
        SynthTaskMessage body = new SynthTaskMessage("t", "u", "s", "AFTER_TURN", 1L);
        body.setProduceTime(System.currentTimeMillis() - 120_000L); // 120s 延迟

        consumer.onMessage(buildMessage(body));
        verify(dispatcher).consume(any());
    }

    // ==================== SynthTaskMessage DTO 契约测试 ====================

    @Test
    @DisplayName("C8 JSON round-trip：全字段保真（tenantId/userId/sessionId/kind/snapshotVersion/produceTime）")
    void c8_roundTrip_allFields() {
        SynthTaskMessage original = new SynthTaskMessage(
                "tenant-A", "user-B", "sess-007", "AFTER_SESSION", 999L);
        long produceTime = System.currentTimeMillis();
        original.setProduceTime(produceTime);

        String json = JsonUtils.toJson(original);
        SynthTaskMessage parsed = JsonUtils.fromJson(json, SynthTaskMessage.class);

        assertEquals(original.getTenantId(), parsed.getTenantId());
        assertEquals(original.getUserId(), parsed.getUserId());
        assertEquals(original.getSessionId(), parsed.getSessionId());
        assertEquals(original.getKind(), parsed.getKind());
        assertEquals(original.getSnapshotVersion(), parsed.getSnapshotVersion());
        assertEquals(original.getProduceTime(), parsed.getProduceTime());
    }

    @Test
    @DisplayName("C9 全参构造自动填 produceTime")
    void c9_fullConstructor_produceTimePopulated() {
        long before = System.currentTimeMillis();
        SynthTaskMessage msg = new SynthTaskMessage("t", "u", "s", "AFTER_TURN", 100L);
        long after = System.currentTimeMillis();

        assertTrue(msg.getProduceTime() >= before);
        assertTrue(msg.getProduceTime() <= after);
    }

    @Test
    @DisplayName("C10 无参构造默认值 + setter 可覆盖")
    void c10_noArgConstructor_defaults() {
        SynthTaskMessage msg = new SynthTaskMessage();
        assertNull(msg.getTenantId());
        assertNull(msg.getUserId());
        assertNull(msg.getSessionId());
        assertNull(msg.getKind());
        assertEquals(0L, msg.getSnapshotVersion());
        assertEquals(0L, msg.getProduceTime());

        msg.setTenantId("t1");
        msg.setSnapshotVersion(123L);
        msg.setProduceTime(456L);
        assertEquals("t1", msg.getTenantId());
        assertEquals(123L, msg.getSnapshotVersion());
        assertEquals(456L, msg.getProduceTime());
    }

    @Test
    @DisplayName("C11 toString 包含 sessionId/kind/version")
    void c11_toString_keyFields() {
        SynthTaskMessage msg = new SynthTaskMessage("t", "u", "sess-xyz", "AFTER_TURN", 7L);
        String s = msg.toString();
        assertTrue(s.contains("sess-xyz"));
        assertTrue(s.contains("AFTER_TURN"));
        assertTrue(s.contains("7"));
    }
}
