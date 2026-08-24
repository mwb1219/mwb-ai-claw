package com.mwb.ai.claw.example.commerce.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.retrieve.RagReranker;
import com.mwb.ai.claw.domain.rag.write.RagChunker;
import com.mwb.ai.claw.infrastructure.rag.write.TextRagChunker;

/**
 * RAG SPI 扩展装配（仅 {@code agent.rag.enabled=true} 时生效）。
 * <p>
 * 演示两种扩展方式：
 * <ul>
 *   <li>替换/增强：{@link CommerceRagChunker} 包装默认 {@link TextRagChunker}，
 *       以 {@code @ConditionalOnMissingBean} 覆盖框架默认 Bean；</li>
 *   <li>可选增强：{@link CommerceRagReranker} 作为 {@link RagReranker} 按需注入。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true")
public class CommerceRagConfiguration {

    @Bean
    @ConditionalOnMissingBean(RagChunker.class)
    public RagChunker commerceRagChunker(RagConfig config) {
        return new CommerceRagChunker(new TextRagChunker(config));
    }

    @Bean
    @ConditionalOnMissingBean(RagReranker.class)
    public RagReranker commerceRagReranker() {
        return new CommerceRagReranker();
    }
}