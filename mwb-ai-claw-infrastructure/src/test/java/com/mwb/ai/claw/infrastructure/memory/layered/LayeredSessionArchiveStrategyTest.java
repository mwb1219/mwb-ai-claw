package com.mwb.ai.claw.infrastructure.memory.layered;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.MessageRole;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.core.SessionGateway;
import com.mwb.ai.claw.domain.memory.layered.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.layered.LayeredSessionGatewayImpl;
import com.mwb.ai.claw.domain.memory.layered.model.MemoryPage;
import com.mwb.ai.claw.domain.memory.layered.spi.MemoryPageStore;
import com.mwb.ai.claw.domain.memory.layered.spi.MemoryRetriever;
import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesisDispatcher;
import com.mwb.ai.claw.domain.memory.layered.spi.MemorySynthesizer;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 分层记忆归档策略单元测试：验证 Phase A/B 行为修正。
 * <ul>
 *   <li>A1：归档保留最近热窗，会话进行中最新原文始终 archived=0</li>
 *   <li>B1：归档游标跟随摘要进度（start = max(lastArchived, lastSummarized)）</li>
 *   <li>B2：低 token 块（archiveMinTokens 限制内）不归档全文</li>
 *   <li>B3：会话闲置超时后收敛剩余热窗（safeEnd = 全量）</li>
 * </ul>
 */
class LayeredSessionArchiveStrategyTest {

    private final AgentScope scope = AgentScope.of("t1", "u1");
    private final String sessionId = "sess-1";

    private LayeredMemoryConfig config;
    private InMemPageStore pageStore;
    private MemorySynthesizer synthesizer;
    private MemoryRetriever retriever;
    private MemorySynthesisDispatcher taskQueue;
    private SessionGateway sessionGateway;
    private LayeredSessionGatewayImpl gateway;

    @BeforeEach
    void setUp() {
        config = new LayeredMemoryConfig();
        config.setEnabled(true);
        config.setArchiveEnabled(true);
        config.setHotWindowSize(20);
        config.setSummaryBlockSize(10);
        // 同步执行 afterSession，便于直接断言 doAfterSession 的归档/标记行为
        config.setSynthesisAsync(false);
        // 默认关闭 B3 空闲收敛与 B2 价值约束，聚焦 A1/B1
        config.setArchiveKeepRecent(0);
        config.setArchiveIdleTimeout(null);
        config.setArchiveMinTokens(0);

        pageStore = new InMemPageStore();
        synthesizer = mock(MemorySynthesizer.class);
        retriever = mock(MemoryRetriever.class);
        taskQueue = mock(MemorySynthesisDispatcher.class);
        sessionGateway = mock(SessionGateway.class);
        when(synthesizer.extractFacts(any(), any())).thenReturn(new ArrayList<>());
        when(sessionGateway.loadAllMessages(any(), any())).thenAnswer(inv -> new ArrayList<>());
        gateway = new LayeredSessionGatewayImpl(config, pageStore, synthesizer, retriever, taskQueue, sessionGateway);
    }

    private List<Message> messages(int count, boolean oldTimestamp) {
        List<Message> msgs = new ArrayList<>();
        long ts = oldTimestamp ? Instant.now().minusSeconds(7200).toEpochMilli() : Instant.now().toEpochMilli();
        for (int i = 0; i < count; i++) {
            Message m = Message.of(MessageRole.USER, "msg-" + i);
            m.setMsgIndex(i);
            if (oldTimestamp) {
                m.setTimestamp(ts);
            }
            msgs.add(m);
        }
        return msgs;
    }

    private Session session() {
        Session s = new Session();
        s.setSessionId(sessionId);
        s.setTenantId(scope.getTenantId());
        s.setUserId(scope.getUserId());
        return s;
    }

    private void seedSession(List<Message> msgs) {
        when(sessionGateway.loadAllMessages(eq(scope), eq(sessionId))).thenReturn(msgs);
    }

    // ==================== A1：保留热窗 ====================

    @Test
    void afterSession_messagesWithinHotWindow_doNotArchived() {
        seedSession(messages(5, false));
        gateway.afterSession(session(), null);

        // 消息数(5) <= 热窗(20) → safeEnd=0，不归档、不标记
        assertTrue(pageStore.archives.isEmpty(), "消息少于热窗时不应产生 ARCHIVE 页");
        verify(sessionGateway, never()).markArchived(any(), any(), anyInt(), anyInt());
    }

    @Test
    void afterSession_beyondHotWindow_archiveOnlyOldSegments() {
        // 30 条，热窗 20 → safeEnd=10；只需归档 [0,10)，最近 [10,30) 保持未归档
        seedSession(messages(30, false));
        gateway.afterSession(session(), null);

        assertEquals(1, pageStore.archives.size(), "应归档一块 [0,10)");
        MemoryPage page = pageStore.archives.get(0);
        assertEquals(0, page.getBlockStart());
        assertEquals(10, page.getBlockEnd());
        // 只标记滚出热窗的旧段 [0,10)
        verify(sessionGateway).markArchived(scope, sessionId, 0, 10);
    }

    @Test
    void afterSession_customArchiveKeepRecent_usesConfigValue() {
        config.setArchiveKeepRecent(5);
        config.setHotWindowSize(20);
        // 25 条，保留最近 5 条 → safeEnd=20；归档 [0,20)（2 块），最近 [20,25) 未归档
        seedSession(messages(25, false));
        gateway.afterSession(session(), null);

        assertEquals(2, pageStore.archives.size());
        assertEquals(0, pageStore.archives.get(0).getBlockStart());
        assertEquals(10, pageStore.archives.get(0).getBlockEnd());
        assertEquals(10, pageStore.archives.get(1).getBlockStart());
        assertEquals(20, pageStore.archives.get(1).getBlockEnd());
        verify(sessionGateway).markArchived(scope, sessionId, 0, 20);
    }

    // ==================== B3：空闲收敛 ====================

    @Test
    void afterSession_idle_convergeAllHotWindow() {
        config.setArchiveIdleTimeout(Duration.ofMinutes(30));
        // 25 条，最后一条距今 7200s > 30min → 会话结束，safeEnd=全量 25
        seedSession(messages(25, true));
        gateway.afterSession(session(), null);

        assertEquals(3, pageStore.archives.size(), "空闲会话应归档全部热窗：[0,10),[10,20),[20,25)");
        assertEquals(25, pageStore.archives.get(2).getBlockEnd());
        verify(sessionGateway).markArchived(scope, sessionId, 0, 25);
    }

    // ==================== B2：价值约束 ====================

    @Test
    void archiveMinTokens_skipsLowValueBlock() {
        config.setHotWindowSize(5);
        config.setSummaryBlockSize(10);
        // 25 条，热窗 5 → safeEnd=20；archive-min-tokens=100000（任何块都低于）→ 不归档全文，但仍标记
        config.setArchiveMinTokens(100000);
        seedSession(messages(25, false));
        gateway.afterSession(session(), null);

        assertTrue(pageStore.archives.isEmpty(), "低于 token 阈值的块不应归档全文");
        verify(sessionGateway).markArchived(scope, sessionId, 0, 20);
    }

    /** 内存版 MemoryPageStore 假实现：仅归档/摘要/事实写入到内存列表，用于断言归档边界 */
    private static final class InMemPageStore implements MemoryPageStore {
        final List<MemoryPage> archives = new ArrayList<>();
        final List<MemoryPage> summaries = new ArrayList<>();
        final List<MemoryPage> facts = new ArrayList<>();

        @Override public void saveArchive(AgentScope scope, MemoryPage page) { archives.add(page); }
        @Override public List<MemoryPage> loadArchive(AgentScope scope, String sessionId) { return archives; }
        @Override public void saveSummary(AgentScope scope, MemoryPage page) { summaries.add(page); }
        @Override public List<MemoryPage> loadSummaries(AgentScope scope, String sessionId) { return summaries; }
        @Override public List<MemoryPage> listAllSummaries(AgentScope scope) { return summaries; }
        @Override public void appendFact(AgentScope scope, MemoryPage fact) { facts.add(fact); }
        @Override public List<MemoryPage> loadFacts(AgentScope scope) { return facts; }
        @Override public void deleteFact(AgentScope scope, String key) { facts.removeIf(f -> key.equals(f.getKey())); }
        @Override public void deleteSessionPages(AgentScope scope, String sessionId) { summaries.removeIf(p -> sessionId.equals(p.getSessionId())); }
        @Override public List<MemoryPage> listAllArchive(AgentScope scope) { return archives; }
        @Override public void deleteSessionArchive(AgentScope scope, String sessionId) { archives.removeIf(p -> sessionId.equals(p.getSessionId())); }
    }
}