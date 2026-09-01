package com.mwb.ai.claw.example.web.memory.synthesis;

import java.nio.charset.StandardCharsets;

import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesisDispatcher;
import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesisDispatcher.Kind;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.util.JsonUtils;

/**
 * Phase 3 RocketMQ 提炼事件消费者：
 * <p>
 * - Topic：{@code CLAW_SYNTH_TASK}（与 {@link RocketMqMemorySynthesisDispatcher#TOPIC} 保持一致）；
 * - Tag 过滤：{@code AFTER_TURN || AFTER_SESSION}；
 * - 消费模式：CLUSTERING；
 * - 重试：{@code maxReconsumeTimes = 3}，超过后进入 DLQ；
 * - 串行保证：同 sessionId hash 到同一队列 → RocketMQ 保证同队列消息串行消费。
 * <p>
 * 统一 SPI 契约：反序列化 MQ 消息 → 构造 {@link MemorySynthesisDispatcher.SynthesisEvent}
 * （携带 stagingVersion）→ 调 {@link MemorySynthesisDispatcher#consume}。
 * staging load / handler 执行 / staging cleanup 全部由 Dispatcher.consume 内部统一完成。
 */
@RocketMQMessageListener(
        topic = RocketMqMemorySynthesisDispatcher.TOPIC,
        consumerGroup = "claw-synth-consumer",
        selectorExpression = "AFTER_TURN||AFTER_SESSION",
        consumeMode = ConsumeMode.CONCURRENTLY,
        maxReconsumeTimes = 3
)
public class RocketMqSynthesisConsumer implements RocketMQListener<MessageExt> {

    private static final Logger log = LoggerFactory.getLogger(RocketMqSynthesisConsumer.class);

    private final RocketMqMemorySynthesisDispatcher dispatcher;

    public RocketMqSynthesisConsumer(RocketMqMemorySynthesisDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void onMessage(MessageExt mqMsg) {
        String body = new String(mqMsg.getBody(), StandardCharsets.UTF_8);
        SynthTaskMessage msg;
        try {
            msg = JsonUtils.fromJson(body, SynthTaskMessage.class);
        } catch (Exception e) {
            log.error("Phase 3 MQ 消息体反序列化失败：{}", body, e);
            // 无法解析 → 直接 ACK 丢弃
            return;
        }

        String sessionId = msg.getSessionId();
        Kind kind;
        try {
            kind = Kind.valueOf(msg.getKind());
        } catch (IllegalArgumentException e) {
            log.error("Phase 3 MQ 未知 kind：{}", msg.getKind());
            return;
        }

        AgentScope scope = AgentScope.of(msg.getTenantId(), msg.getUserId());

        // 诊断消费延迟
        long latencyMs = System.currentTimeMillis() - msg.getProduceTime();
        if (latencyMs > 60000) {
            log.warn("Phase 3 MQ 消费延迟较高：sessionId={}, kind={}, latency={}ms", sessionId, kind, latencyMs);
        } else {
            log.info("Phase 3 MQ 消费：sessionId={}, kind={}, latency={}ms", sessionId, kind, latencyMs);
        }

        // 构造 SynthesisEvent 并交给 Dispatcher.consume
        // Dispatcher.consume 内部完成：staging.load → handler 执行 → staging.delete
        MemorySynthesisDispatcher.SynthesisEvent event = new MemorySynthesisDispatcher.SynthesisEvent(
                scope, sessionId, kind, null, null);
        event.stagingVersion = msg.getSnapshotVersion();
        // snapshotSupplier 为 null：Dispatcher.consume 内部从 staging load（带 preloadedSnapshot）
        // handler 为 null：Dispatcher.consume 内部会重新构造带内部 handler 的 event

        dispatcher.consume(event);
    }
}
