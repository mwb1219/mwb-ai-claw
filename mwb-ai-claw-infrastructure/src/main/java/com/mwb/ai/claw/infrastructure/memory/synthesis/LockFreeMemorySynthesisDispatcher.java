package com.mwb.ai.claw.infrastructure.memory.synthesis;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.memory.model.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.model.MemoryPage;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesisDispatcher;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesisDispatcher.Kind;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesisDispatcher.SynthesisEvent;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesizer;
import com.mwb.ai.claw.domain.memory.store.MemoryPageStore;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;
import com.mwb.ai.claw.infrastructure.util.TokenEstimator;

/**
 * 无锁 CAS 提炼事件派发器（Phase 2，显式 lockfree）：
 * 不获取分布式锁，而是在 handler 内部通过 CAS 预占 {@code claw_memory_boundary} 表的
 * 边界游标实现互斥。CAS 成功才执行 LLM，失败重试。
 * <p>
 * 统一 produce + consume 契约：
 * <ul>
 *   <li>produce：提交到 executor → 快照在 consume 前延迟获取 → 调 consume</li>
 *   <li>consume：确保快照就绪 → event.execute()（handler 内部执行 CAS claim + 提炼循环）</li>
 * </ul>
 * 与 Phase 1 的关键区别：handler 是 Dispatcher 内部绑定的 <b>带 CAS claim 循环</b> 的执行体，
 * 覆盖调用方（LayeredMemoryGatewayImpl）传入的简单 doAfterTurn。
 * <p>
 * DB 层兜底：
 * - {@code uk_scope_session_type_start} 唯一键防摘要/归档块重叠
 * - CAS 失败不回滚 boundary 表（已推进的边界代表"已被其他实例抢占"）
 */
public class LockFreeMemorySynthesisDispatcher implements MemorySynthesisDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LockFreeMemorySynthesisDispatcher.class);

    private final LayeredMemoryConfig config;
    private final MetricsRecorder metrics;
    private final MemorySynthesisExecutor executor;
    private final MemoryPageStore pageStore;
    private final MemorySynthesizer synthesizer;
    /** 内部绑定的 handler：带 CAS claim + 重试循环，覆盖调用方传入的简单 handler */
    private final Consumer<SynthesisEvent> handler;

    public LockFreeMemorySynthesisDispatcher(LayeredMemoryConfig config,
                                              MetricsRecorder metrics,
                                              MemorySynthesisExecutor executor,
                                              MemoryPageStore pageStore,
                                              MemorySynthesizer synthesizer) {
        this.config = config;
        this.metrics = metrics;
        this.executor = executor;
        this.pageStore = pageStore;
        this.synthesizer = synthesizer;
        this.handler = this::handleEvent;
    }

    @Override
    public void produce(SynthesisEvent event) {
        String taskName = event.kind.name().toLowerCase() + "-" + event.sessionId;
        // 用内部绑定的 handler 替换调用方传入的 handler（内部 handler 带 CAS claim 循环）
        SynthesisEvent internal = new SynthesisEvent(
                event.scope, event.sessionId, event.kind,
                event.snapshotSupplier, this.handler);
        executor.submit(internal.scope, taskName, () -> consume(internal));
    }

    @Override
    public void consume(SynthesisEvent event) {
        try {
            if (event.getSnapshot() == null || event.getSnapshot().isEmpty()) {
                metrics.synthLlmSkip(event.kind.name(), "empty_snapshot");
                return;
            }
            event.execute();
        } catch (Exception e) {
            log.warn("无锁提炼事件 {} 执行失败: {}", event.kind, e.getMessage());
        }
    }

    @Override
    public int pendingCount() {
        return executor.pendingCount();
    }

    // ==================== handler 实现：带 CAS claim + 重试循环 ====================

    private void handleEvent(SynthesisEvent event) {
        if (event.kind == Kind.AFTER_TURN) {
            handleAfterTurn(event.scope, event.sessionId, event.getSnapshot());
        } else {
            handleAfterSession(event.scope, event.sessionId, event.getSnapshot());
        }
    }

    /** AFTER_TURN：CAS claim 摘要区间 → LLM 生成摘要页（循环 claim 直到 snapshot 耗尽） */
    private void handleAfterTurn(AgentScope scope, String sessionId, List<Message> snapshot) {
        int blockSize = config.getSummaryBlockSize();
        int maxRetries = config.getSynthesisClaimMaxRetries();

        while (true) {
            int claimedStart = -1;
            int retries = 0;
            while (retries <= maxRetries) {
                claimedStart = pageStore.claimSummaryBlock(scope, sessionId, 0, blockSize, snapshot.size());
                if (claimedStart >= 0) break;
                retries++;
                if (retries <= maxRetries) {
                    metrics.synthClaimCasRetry("summary");
                    try { Thread.sleep(1L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
                }
            }

            if (claimedStart < 0) {
                if (retries > maxRetries) metrics.synthClaimFail("summary", "retry_exhausted");
                else metrics.synthClaimFail("summary", "no_block");
                metrics.synthLlmSkip(Kind.AFTER_TURN.name(), "claim_fail");
                return;
            }

            metrics.synthClaimSuccess("summary");
            int end = Math.min(claimedStart + blockSize, snapshot.size());
            List<Message> block = new ArrayList<>(snapshot.subList(claimedStart, end));
            String summary = synthesizer.summarizeBlock(scope, block);
            if (summary != null && !summary.isEmpty()) {
                pageStore.saveSummary(scope, MemoryPage.summary(
                        "summary-" + sessionId + "-" + claimedStart,
                        summary, sessionId, claimedStart, end,
                        TokenEstimator.estimate(summary)));
                log.debug("Phase 2 CAS 摘要: sessionId={}, [{}:{})", sessionId, claimedStart, end);
            }

            if (end >= snapshot.size()) break;
        }
    }

    /** AFTER_SESSION：CAS claim 归档块 → 归档原文；事实提炼直接走 UPSERT（不需要 CAS） */
    private void handleAfterSession(AgentScope scope, String sessionId, List<Message> snapshot) {
        // 1. 归档原文（CAS claim 归档块）
        if (config.isArchiveEnabled() && !snapshot.isEmpty()) {
            archiveWithCas(scope, sessionId, snapshot);
        }
        // 2. 事实提炼（merge 走原子 UPSERT）
        List<MemoryPage> freshFacts = synthesizer.extractFacts(scope, snapshot);
        int saved = 0;
        for (MemoryPage fresh : freshFacts) {
            if (fresh.getImportance() < config.getImportanceThreshold()) continue;
            MemoryPage existing = findFact(scope, fresh.getKey());
            MemoryPage merged = synthesizer.mergeFact(existing, fresh);
            merged.setTokenCount(TokenEstimator.estimate(merged));
            pageStore.upsertFactAtomic(scope, merged);
            saved++;
        }
        log.debug("Phase 2 CAS 提炼: sessionId={}, 事实 {} 条", sessionId, saved);
    }

    private void archiveWithCas(AgentScope scope, String sessionId, List<Message> snapshot) {
        int blockSize = config.getSummaryBlockSize();
        int maxRetries = config.getSynthesisClaimMaxRetries();

        while (true) {
            int claimedStart = -1;
            int retries = 0;
            while (retries <= maxRetries) {
                claimedStart = pageStore.claimArchiveBlock(scope, sessionId, 0, blockSize, snapshot.size());
                if (claimedStart >= 0) break;
                retries++;
                if (retries <= maxRetries) {
                    metrics.synthClaimCasRetry("archive");
                    try { Thread.sleep(1L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
                }
            }

            if (claimedStart < 0) {
                if (retries > maxRetries) metrics.synthClaimFail("archive", "retry_exhausted");
                else metrics.synthClaimFail("archive", "no_block");
                metrics.synthLlmSkip(Kind.AFTER_SESSION.name(), "archive_claim_fail");
                return;
            }

            metrics.synthClaimSuccess("archive");
            int end = Math.min(claimedStart + blockSize, snapshot.size());
            List<Message> block = new ArrayList<>(snapshot.subList(claimedStart, end));
            String content = messagesToText(block);
            MemoryPage page = MemoryPage.archive(
                    "archive-" + sessionId + "-" + claimedStart,
                    content, sessionId, claimedStart, end, TokenEstimator.estimate(content));
            pageStore.saveArchive(scope, page);
            log.debug("Phase 2 CAS 归档: sessionId={}, [{}:{})", sessionId, claimedStart, end);

            if (end >= snapshot.size()) break;
        }
    }

    private MemoryPage findFact(AgentScope scope, String key) {
        return pageStore.loadFacts(scope).stream()
                .filter(f -> key.equals(f.getKey())).findFirst().orElse(null);
    }

    private String messagesToText(List<Message> block) {
        StringBuilder sb = new StringBuilder();
        for (Message m : block) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[").append(m.getRole().getValue()).append("] ")
              .append(m.getContent() == null ? "" : m.getContent());
        }
        return sb.toString();
    }
}
