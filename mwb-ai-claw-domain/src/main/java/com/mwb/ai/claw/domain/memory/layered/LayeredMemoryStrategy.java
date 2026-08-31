package com.mwb.ai.claw.domain.memory.layered;

import java.util.ArrayList;
import java.util.List;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.memory.layered.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.memory.MemoryStrategy;
import com.mwb.ai.claw.domain.memory.layered.model.MemoryPage;

/**
 * 分层记忆策略适配器：将 {@link LayeredMemoryGateway} 包装为 {@link MemoryStrategy}。
 * <p>
 * 通过适配器模式将 LayeredMemoryGateway 的 MemoryView 转换为 MemoryStrategy.MemoryContext，
 * 使上层可以统一依赖 MemoryStrategy 接口，实现记忆可插拔。
 * <p>
 * MemoryContext 映射规则：
 * <ul>
 *   <li>workingMessages ← MemoryView.workingMessages（HOT 活跃消息）</li>
 *   <li>systemPromptAugment ← facts + summaries 的拼接（跨会话事实 + 归档摘要）</li>
 *   <li>memoryPages ← retrieved（共享检索页）</li>
 * </ul>
 */
public class LayeredMemoryStrategy implements MemoryStrategy {

    private final LayeredMemoryGateway layeredMemoryGateway;

    public LayeredMemoryStrategy(LayeredMemoryGateway layeredMemoryGateway) {
        this.layeredMemoryGateway = layeredMemoryGateway;
    }

    @Override
    public boolean isEnabled() {
        return layeredMemoryGateway.isEnabled();
    }

    @Override
    public MemoryContext readContext(Session session, Agent agent) {
        LayeredMemoryGateway.MemoryView view = layeredMemoryGateway.readContext(session, agent);
        return toMemoryContext(view);
    }

    @Override
    public void afterTurn(Session session, Agent agent) {
        layeredMemoryGateway.afterTurn(session, agent);
    }

    @Override
    public void afterSession(Session session, Agent agent) {
        layeredMemoryGateway.afterSession(session, agent);
    }

    @Override
    public void saveMemory(String topic, String content, double importance) {
        layeredMemoryGateway.saveFact(topic, content, importance);
    }

    @Override
    public String readMemoryText() {
        return layeredMemoryGateway.readFactsText();
    }

    @Override
    public List<MemoryPage> searchMemory(String query, int topK) {
        return layeredMemoryGateway.search(query, topK);
    }

    /**
     * 将 LayeredMemoryGateway.MemoryView 转换为 MemoryStrategy.MemoryContext。
     * <p>
     * 转换规则：
     * <ul>
     *   <li>workingMessages → workingMessages（直接映射）</li>
     *   <li>facts + summaries → systemPromptAugment（拼接成一段文本注入 System Prompt）</li>
     *   <li>retrieved → memoryPages（共享检索页）</li>
     * </ul>
     */
    private MemoryContext toMemoryContext(LayeredMemoryGateway.MemoryView view) {
        MemoryContext ctx = new MemoryContext();
        ctx.setWorkingMessages(view.getWorkingMessages());

        // facts + summaries 拼接到 systemPromptAugment
        StringBuilder augment = new StringBuilder();
        List<MemoryPage> facts = view.getFactPages();
        List<MemoryPage> summaries = view.getSummaryPages();

        if (!facts.isEmpty() || !summaries.isEmpty()) {
            augment.append("\n\n--- 历史记忆 ---\n");
            for (MemoryPage fact : facts) {
                augment.append("- [事实] ").append(fact.getContent()).append("\n");
            }
            for (MemoryPage summary : summaries) {
                augment.append("- [摘要] ").append(summary.getContent()).append("\n");
            }
        }
        ctx.setSystemPromptAugment(augment.toString());

        ctx.setMemoryPages(new ArrayList<>(view.getRetrievedPages()));
        return ctx;
    }
}
