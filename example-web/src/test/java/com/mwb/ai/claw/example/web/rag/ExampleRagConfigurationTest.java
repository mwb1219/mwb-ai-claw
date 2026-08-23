package com.mwb.ai.claw.example.web.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import com.mwb.ai.claw.domain.rag.context.RagContextProvider;
import com.mwb.ai.claw.domain.rag.retrieve.RagReranker;
import com.mwb.ai.claw.domain.rag.retrieve.RagRetrievalService;
import com.mwb.ai.claw.domain.rag.write.RagChunker;
import com.mwb.ai.claw.domain.rag.write.RagIngestionService;
import com.mwb.ai.claw.example.web.WebApplication;

/**
 * 验证 example-web 的 RAG SPI 扩展在 Spring 上下文中正确装配：
 * <ul>
 *   <li>自定义 {@link ExampleRagChunker} 覆盖默认切分器（{@code @ConditionalOnMissingBean}）；</li>
 *   <li>自定义 {@link ExampleRagReranker} 注册为可选重排器；</li>
 *   <li>框架默认的摄入 / 检索 / 上下文注入 Bean 正常装配。</li>
 * </ul>
 */
@SpringBootTest(classes = WebApplication.class, properties = {
        "agent.rag.enabled=true",
        "agent.rag.embedding.model=text-embedding-3-small",
        "agent.rag.embedding.base-url=https://api.openai.com/v1",
        "agent.rag.embedding.api-key=test"
})
class ExampleRagConfigurationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void ragSpisAreAssembledWithExampleExtensions() {
        // 自定义切分器覆盖默认实现（扩展点：替换）
        assertThat(context.getBean(RagChunker.class))
                .isInstanceOf(ExampleRagChunker.class);
        // 可选重排器注册（扩展点：增强）
        assertThat(context.getBean(RagReranker.class))
                .isInstanceOf(ExampleRagReranker.class);
        // 框架默认装配的写入 / 检索 / 上下文注入
        assertThat(context.getBean(RagIngestionService.class)).isNotNull();
        assertThat(context.getBean(RagRetrievalService.class)).isNotNull();
        assertThat(context.getBean(RagContextProvider.class)).isNotNull();
    }
}
