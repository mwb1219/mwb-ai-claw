package com.mwb.ai.claw.infrastructure.memory.longterm;

import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MEMORY.md 长期记忆写入服务（T8）：增量合并 + LLM 去重精炼，比 {@link LongTermMemoryGateway#saveMemory} 的裸覆盖更安全。
 * <p>
 * 两条写入入口都走这里：
 * <ul>
 *   <li><b>关键字触发（入口不漏）</b>：{@link #captureAsync}——ReAct 外层扫到「我叫/我是/记住我」等命中后异步追加；</li>
 *   <li><b>LLM 自判断工具（过滤不错）</b>：{@link #appendMerged}——write_long_term_memory 工具调用。</li>
 * </ul>
 * 合并策略：loadMemory() 拿现有内容 → 追加新段落 → 可选 LLM 去重精炼 → saveMemory 整体写回。
 * LLM 精炼失败或未装配 LLM 时回退为「结构化追加」（Markdown 按主题分节，避免无限膨胀）。
 * 全部写入异步执行（独立 daemon 线程池），不阻塞主对话链路。
 */
@Component
public class LongTermMemoryWriter {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryWriter.class);

    /** T8-A 关键字触发集合：命中即进入 MEMORY.md 写入流程（以用户身份/风格声明为主） */
    private static final Set<String> TRIGGER_KEYWORDS = new HashSet<>(Arrays.asList(
            "我叫", "我是", "我叫做", "我的名字", "我做", "我负责", "我喜欢", "我不喜欢",
            "我的风格", "记住我", "以后请", "请记住", "长期记住"));

    private final LongTermMemoryGateway gateway;
    private final AgentProperties properties;
    private final LlmGateway llmGateway; // 可选：未装配时为 null，回退结构化追加
    private final ExecutorService executor;

    public LongTermMemoryWriter(LongTermMemoryGateway gateway,
                                AgentProperties properties,
                                ObjectProvider<LlmGateway> llmGatewayProvider) {
        this.gateway = gateway;
        this.properties = properties;
        this.llmGateway = llmGatewayProvider.getIfAvailable();
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "long-term-memory-writer-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** 文本是否命中 MEMORY.md 关键字触发集合 */
    public boolean containsTrigger(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        for (String kw : TRIGGER_KEYWORDS) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /**
     * T8-A 关键字触发入口（异步，不阻塞）：命中则把该用户消息追加进 MEMORY.md。
     *
     * @param scope    会话作用域（多租户隔离）
     * @param userText 命中关键字的用户消息原文
     */
    public void captureAsync(AgentScope scope, String userText) {
        if (userText == null || userText.trim().isEmpty() || !containsTrigger(userText)) {
            return;
        }
        executor.submit(() -> {
            try {
                appendMerged(scope, userText);
                log.info("MEMORY.md 关键字触发写入成功 (scope={})", scope == null ? "default" : scope.keyPrefix());
            } catch (Exception e) {
                log.warn("MEMORY.md 关键字触发写入失败: {}", e.getMessage());
            }
        });
    }

    /**
     * 增量合并写入 MEMORY.md：现有内容 + 新段落 → 可选 LLM 去重精炼 → 整体写回。
     *
     * @param scope   会话作用域
     * @param newText 新增的长期记忆段落（用户画像/身份/偏好声明）
     */
    public void appendMerged(AgentScope scope, String newText) {
        if (newText == null || newText.trim().isEmpty()) {
            return;
        }
        String existing = gateway.loadMemory(scope);
        String merged = merge(existing, newText.trim());
        gateway.saveMemory(scope, merged);
        log.debug("MEMORY.md 合并写入: 原 {} 字符 → 新 {} 字符", existing == null ? 0 : existing.length(), merged.length());
    }

    // ==================== 私有方法 ====================

    /** 合并策略：LLM 去重精炼（优选）→ 失败回退结构化分节追加 */
    private String merge(String existing, String addition) {
        if (existing == null || existing.trim().isEmpty()) {
            return "## 用户画像\n\n- " + addition;
        }
        String refined = refineWithLlm(existing, addition);
        if (refined != null) {
            return refined;
        }
        // 兜底：Markdown 按主题分节追加，避免与既有内容冲突时无限膨胀
        String base = existing.trim();
        if (base.contains(addition)) {
            return base; // 已存在，跳过去重
        }
        return base + "\n\n- " + addition;
    }

    /** 尝试用 LLM 对「现有 MEMORY.md + 新段落」做去重精炼；未装配 LLM 或调用失败返回 null */
    private String refineWithLlm(String existing, String addition) {
        if (llmGateway == null) {
            return null;
        }
        try {
            String prompt = "下面是用户的长期记忆（MEMORY.md）。请把新增的用户画像/身份/偏好信息合并进去，"
                    + "去除与已有内容重复的部分，保持 Markdown 分节（身份/风格偏好/关注领域），内容精炼不冗余。"
                    + "只输出合并后的完整 Markdown，不要加解释。\n\n=== 现有内容 ===\n"
                    + existing + "\n\n=== 新增内容 ===\n" + addition;
            LlmRequest request = new LlmRequest();
            request.setTemperature(0.3);
            request.setMaxTokens(1500);
            request.setMessages(Arrays.asList(
                    LlmMessage.system("你是长期记忆精炼引擎，输出客观、精炼的 Markdown。"),
                    LlmMessage.user(prompt)));
            LlmResponse resp = llmGateway.chat(request, modelConfig());
            if (resp.getContent() != null && !resp.getContent().trim().isEmpty()) {
                return resp.getContent().trim();
            }
        } catch (Exception e) {
            log.warn("MEMORY.md LLM 精炼失败，回退结构化追加: {}", e.getMessage());
        }
        return null;
    }

    private ModelConfig modelConfig() {
        ModelConfig config = new ModelConfig();
        config.setModel(properties.getModel());
        config.setBaseUrl(properties.getBaseUrl());
        config.setApiKey(properties.getApiKey());
        config.setTemperature(0.3);
        config.setMaxTokens(1500);
        return config;
    }
}