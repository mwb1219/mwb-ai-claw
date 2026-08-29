package com.mwb.ai.claw.example.web.memory.synthesis;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.MessageQueueSelector;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import com.mwb.ai.claw.domain.memory.model.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.model.MemoryPage;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesisDispatcher;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesisDispatcher.Kind;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesisDispatcher.SynthesisEvent;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesizer;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import com.mwb.ai.claw.infrastructure.util.TokenEstimator;

/**
 * 生产级 MQ 提炼事件派发器（Phase 3）：
 * <p>
 * 正确性保证链路：MQ 分区串行（同 sessionId hash → 同一队列）+ DB 幂等 UPSERT 双层兜底。
 * 不依赖 Redis（Phase 1 需要），也不需要 CAS 重试循环（Phase 2 需要重试因为 executor 不保证串行）；
 * 快照暂存 DB staging 表（{@code claw_memory_snapshot}），避免 MQ 消息体过大。
 * <p>
 * 统一 produce + consume 契约：
 * <ul>
 *   <li>produce：snapshotSupplier.get() → staging.save() → MQ 发消息（metadata + version）</li>
 *   <li>consume：staging.load(version) → preloadSnapshot → event.execute() → staging.delete()
 *       handler 是 Dispatcher 内部绑定的 <b>带 CAS claim 但不重试</b> 的执行体</li>
 * </ul>
 */
public class RocketMqMemorySynthesisDispatcher implements MemorySynthesisDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RocketMqMemorySynthesisDispatcher.class);

    static final String TOPIC = "CLAW_SYNTH_TASK";
    static final String TAG_AFTER_TURN = "AFTER_TURN";
    static final String TAG_AFTER_SESSION = "AFTER_SESSION";

    private final DefaultMQProducer producer;
    private final SnapshotStaging staging;
    private final LayeredMemoryConfig config;
    private final MemorySynthesizer synthesizer;
    private final MetricsRecorder metrics;
    private final MemoryPageStoreAccessor pageStoreAccessor;
    /** 内部绑定的 handler：带 CAS claim（MQ 已保证串行，不重试），覆盖调用方传入的 */
    private final Consumer<SynthesisEvent> handler;

    public RocketMqMemorySynthesisDispatcher(DefaultMQProducer producer,
                                              SnapshotStaging staging,
                                              LayeredMemoryConfig config,
                                              MemorySynthesizer synthesizer,
                                              MetricsRecorder metrics,
                                              MemoryPageStoreAccessor pageStoreAccessor) {
        this.producer = producer;
        this.staging = staging;
        this.config = config;
        this.synthesizer = synthesizer;
        this.metrics = metrics;
        this.pageStoreAccessor = pageStoreAccessor;
        this.handler = this::handleEvent;
    }

    // ==================== SPI: produce ====================

    @Override
    public void produce(SynthesisEvent event) {
        // 1. 获取快照（Phase 3 必须在 produce 时获取，因为要序列化存 staging）
        List<com.mwb.ai.claw.domain.core.Message> snapshot = event.snapshotSupplier.get();
        if (snapshot == null || snapshot.isEmpty()) {
            log.debug("Phase 3 MQ 跳过空快照：sessionId={}, kind={}", event.sessionId, event.kind);
            metrics.synthLlmSkip(event.kind.name(), "empty_snapshot");
            return;
        }

        // 2. 写入 staging（DB 暂存），返回 version 写进 MQ 消息体
        long version = staging.save(event.scope, event.sessionId, event.kind, snapshot);

        // 3. 构造 MQ 消息（只含 metadata + version，快照在 staging 表）
        SynthTaskMessage msg = new SynthTaskMessage(
                safe(event.scope.getTenantId()), safe(event.scope.getUserId()),
                event.sessionId, event.kind.name(), version);

        Message mqMsg = new Message(
                TOPIC,
                event.kind == Kind.AFTER_TURN ? TAG_AFTER_TURN : TAG_AFTER_SESSION,
                JsonUtils.toJson(msg).getBytes(StandardCharsets.UTF_8));
        mqMsg.setKeys(event.sessionId);

        try {
            // sendOneway 不等待 broker ACK（异步离线任务，消息丢失由 DB 幂等兜底）
            producer.sendOneway(mqMsg, SELECTOR, event.sessionId);
            log.info("Phase 3 MQ 投递成功：sessionId={}, kind={}, version={}, snapshotSize={}",
                    event.sessionId, event.kind, version, snapshot.size());
        } catch (Exception e) {
            log.warn("Phase 3 MQ 投递失败（忽略）：sessionId={}, kind={}: {}",
                    event.sessionId, event.kind, e.getMessage());
            metrics.synthLlmSkip(event.kind.name(), "mq_send_fail");
        }
    }

    // ==================== SPI: consume ====================
    // 由 RocketMqSynthesisConsumer 反序列化 MQ 消息 → 构造 event（带 stagingVersion）→ 调用

    @Override
    public void consume(SynthesisEvent event) {
        SynthesisEvent internal = new SynthesisEvent(
                event.scope, event.sessionId, event.kind,
                event.snapshotSupplier, this.handler);
        internal.stagingVersion = event.stagingVersion;
        internal.needsStagingCleanup = true;

        try {
            // 1. 从 staging load 快照（如果还没 preload）
            if (internal.getSnapshot() == null && internal.stagingVersion != null) {
                List<com.mwb.ai.claw.domain.core.Message> loaded = staging.load(
                        internal.scope, internal.sessionId, internal.kind, internal.stagingVersion);
                internal.preloadSnapshot(loaded);
            }

            if (internal.getSnapshot() == null || internal.getSnapshot().isEmpty()) {
                log.warn("Phase 3 MQ consume 快照为空（可能 staging 已清理）：sessionId={}, kind={}, version={}",
                        internal.sessionId, internal.kind, internal.stagingVersion);
                return;
            }

            // 2. 执行提炼（handler 内部 CAS claim + 写页，MQ 分区已保证串行）
            internal.execute();

        } catch (Exception e) {
            log.warn("Phase 3 MQ 提炼执行失败：sessionId={}, kind={}: {}",
                    internal.sessionId, internal.kind, e.getMessage());
        } finally {
            // 3. 清理 staging（无论成功失败都清，幂等 delete）
            if (internal.needsStagingCleanup && internal.stagingVersion != null) {
                try {
                    staging.delete(internal.scope, internal.sessionId, internal.kind, internal.stagingVersion);
                } catch (Exception e) {
                    log.warn("Phase 3 staging 清理失败（忽略）：sessionId={}, kind={}, version={}",
                            internal.sessionId, internal.kind, internal.stagingVersion);
                }
            }
        }
    }

    @Override
    public int pendingCount() {
        // MQ 消费堆积可从 RocketMQ Broker 指标查询（ConsumeLag）
        return 0;
    }

    // ==================== handler 实现：CAS claim（不重试） ====================

    private void handleEvent(SynthesisEvent event) {
        if (event.kind == Kind.AFTER_TURN) {
            handleAfterTurn(event.scope, event.sessionId, event.getSnapshot());
        } else {
            handleAfterSession(event.scope, event.sessionId, event.getSnapshot());
        }
    }

    private void handleAfterTurn(AgentScope scope, String sessionId, List<com.mwb.ai.claw.domain.core.Message> snapshot) {
        int blockSize = config.getSummaryBlockSize();

        while (true) {
            int claimedStart = pageStoreAccessor.claimSummaryBlock(scope, sessionId, 0, blockSize, snapshot.size());
            if (claimedStart < 0) {
                metrics.synthClaimFail("summary", "no_block");
                metrics.synthLlmSkip(Kind.AFTER_TURN.name(), "claim_fail");
                return;
            }

            metrics.synthClaimSuccess("summary");
            int end = Math.min(claimedStart + blockSize, snapshot.size());
            List<com.mwb.ai.claw.domain.core.Message> block = new ArrayList<>(snapshot.subList(claimedStart, end));
            String summary = synthesizer.summarizeBlock(scope, block);
            if (summary != null && !summary.isEmpty()) {
                pageStoreAccessor.saveSummary(scope, MemoryPage.summary(
                        "summary-" + sessionId + "-" + claimedStart,
                        summary, sessionId, claimedStart, end,
                        TokenEstimator.estimate(summary)));
                log.debug("Phase 3 MQ 摘要: sessionId={}, [{}:{})", sessionId, claimedStart, end);
            }

            if (end >= snapshot.size()) break;
        }
    }

    private void handleAfterSession(AgentScope scope, String sessionId, List<com.mwb.ai.claw.domain.core.Message> snapshot) {
        // 1. 归档原文（CAS claim，MQ 保证串行）
        if (config.isArchiveEnabled() && !snapshot.isEmpty()) {
            archiveWithClaim(scope, sessionId, snapshot);
        }
        // 2. 事实提炼（UPSERT）
        List<MemoryPage> freshFacts = synthesizer.extractFacts(scope, snapshot);
        int saved = 0;
        for (MemoryPage fresh : freshFacts) {
            if (fresh.getImportance() < config.getImportanceThreshold()) continue;
            MemoryPage existing = findFact(scope, fresh.getKey());
            MemoryPage merged = synthesizer.mergeFact(existing, fresh);
            merged.setTokenCount(TokenEstimator.estimate(merged));
            pageStoreAccessor.upsertFactAtomic(scope, merged);
            saved++;
        }
        log.debug("Phase 3 MQ 提炼: sessionId={}, 事实 {} 条", sessionId, saved);
    }

    private void archiveWithClaim(AgentScope scope, String sessionId, List<com.mwb.ai.claw.domain.core.Message> snapshot) {
        int blockSize = config.getSummaryBlockSize();
        while (true) {
            int claimedStart = pageStoreAccessor.claimArchiveBlock(scope, sessionId, 0, blockSize, snapshot.size());
            if (claimedStart < 0) { metrics.synthClaimFail("archive", "no_block"); return; }

            metrics.synthClaimSuccess("archive");
            int end = Math.min(claimedStart + blockSize, snapshot.size());
            List<com.mwb.ai.claw.domain.core.Message> block = new ArrayList<>(snapshot.subList(claimedStart, end));
            String content = messagesToText(block);
            MemoryPage page = MemoryPage.archive(
                    "archive-" + sessionId + "-" + claimedStart,
                    content, sessionId, claimedStart, end, TokenEstimator.estimate(content));
            pageStoreAccessor.saveArchive(scope, page);
            log.debug("Phase 3 MQ 归档: sessionId={}, [{}:{})", sessionId, claimedStart, end);

            if (end >= snapshot.size()) break;
        }
    }

    private MemoryPage findFact(AgentScope scope, String key) {
        for (MemoryPage f : pageStoreAccessor.loadFacts(scope)) {
            if (key.equals(f.getKey())) return f;
        }
        return null;
    }

    private String messagesToText(List<com.mwb.ai.claw.domain.core.Message> block) {
        StringBuilder sb = new StringBuilder();
        for (com.mwb.ai.claw.domain.core.Message m : block) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[").append(m.getRole() == null ? "unknown" : m.getRole().getValue()).append("] ")
              .append(m.getContent() == null ? "" : m.getContent());
        }
        return sb.toString();
    }

    // ==================== 内部工具 ====================

    /** Phase 3 提炼逻辑对存储层的访问接口。解耦 example-web 对框架核心的依赖 */
    public interface MemoryPageStoreAccessor {
        int claimSummaryBlock(AgentScope scope, String sessionId, int desiredStart, int blockSize, int snapshotSize);
        int claimArchiveBlock(AgentScope scope, String sessionId, int desiredStart, int blockSize, int snapshotSize);
        void saveSummary(AgentScope scope, MemoryPage page);
        void saveArchive(AgentScope scope, MemoryPage page);
        void upsertFactAtomic(AgentScope scope, MemoryPage fact);
        List<MemoryPage> loadFacts(AgentScope scope);
    }

    public static final class DefaultMemoryPageStoreAccessor implements MemoryPageStoreAccessor {
        private final com.mwb.ai.claw.domain.memory.store.MemoryPageStore delegate;

        public DefaultMemoryPageStoreAccessor(com.mwb.ai.claw.domain.memory.store.MemoryPageStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public int claimSummaryBlock(AgentScope scope, String sessionId, int desiredStart, int blockSize, int snapshotSize) {
            return delegate.claimSummaryBlock(scope, sessionId, desiredStart, blockSize, snapshotSize);
        }
        @Override
        public int claimArchiveBlock(AgentScope scope, String sessionId, int desiredStart, int blockSize, int snapshotSize) {
            return delegate.claimArchiveBlock(scope, sessionId, desiredStart, blockSize, snapshotSize);
        }
        @Override
        public void saveSummary(AgentScope scope, MemoryPage page) { delegate.saveSummary(scope, page); }
        @Override
        public void saveArchive(AgentScope scope, MemoryPage page) { delegate.saveArchive(scope, page); }
        @Override
        public void upsertFactAtomic(AgentScope scope, MemoryPage fact) { delegate.upsertFactAtomic(scope, fact); }
        @Override
        public List<MemoryPage> loadFacts(AgentScope scope) { return delegate.loadFacts(scope); }
    }

    /** MQ 分区选择器：按 sessionId hash → 同会话路由到同一队列 */
    private static final MessageQueueSelector SELECTOR = new MessageQueueSelector() {
        @Override
        public MessageQueue select(List<MessageQueue> mqs, Message msg, Object arg) {
            String key = (String) arg;
            int h = Math.abs(key.hashCode());
            return mqs.get(h % mqs.size());
        }
    };

    private static String safe(String s) { return s == null ? "" : s; }
}
