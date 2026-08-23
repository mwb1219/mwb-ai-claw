package com.mwb.ai.claw.example.web.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.retrieve.RagReranker;
import com.mwb.ai.claw.domain.rag.write.RagChunker;
import com.mwb.ai.claw.infrastructure.rag.write.TextRagChunker;

/**
 * RAG SPI 扩展演示（仅 {@code agent.rag.enabled=true} 时装配）。
 *
 * <p>展示 mwb-ai-claw 独立 RAG 的可插拔扩展能力：
 * <ul>
 *   <li>替换扩展：自定义 {@link RagChunker}（{@link ExampleRagChunker}）包装默认文本切分器，
 *       在分块元数据追加扩展标记 —— 框架用 {@code @ConditionalOnMissingBean} 保证自定义 Bean 覆盖默认实现；</li>
 *   <li>增强扩展：可选 {@link RagReranker}（{@link ExampleRagReranker}），在向量检索后二次排序截断并记录日志。</li>
 * </ul>
 *
 * @author Frank Zhang
 */
@Configuration
@ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true")
public class ExampleRagConfiguration {

    @Bean
    @ConditionalOnMissingBean(RagChunker.class)
    public RagChunker exampleRagChunker(RagConfig config) {
        return new ExampleRagChunker(new TextRagChunker(config));
    }

    @Bean
    @ConditionalOnMissingBean(RagReranker.class)
    public RagReranker exampleRagReranker() {
        return new ExampleRagReranker();
    }
}
