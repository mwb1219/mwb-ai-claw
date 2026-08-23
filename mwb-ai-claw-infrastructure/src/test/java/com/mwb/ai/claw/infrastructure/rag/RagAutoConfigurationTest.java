package com.mwb.ai.claw.infrastructure.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import com.mwb.ai.claw.domain.rag.embed.RagEmbeddingGateway;
import com.mwb.ai.claw.domain.rag.retrieve.RagRetrievalService;
import com.mwb.ai.claw.domain.rag.write.RagIngestionService;
import com.mwb.ai.claw.infrastructure.autoconfigure.ClawCoreAutoConfiguration;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.rag.embed.OpenAiRagEmbeddingGateway;

/**
 * RAG 开关和 SPI 覆盖规则测试。
 */
public class RagAutoConfigurationTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(BaseConfiguration.class,
                    ClawCoreAutoConfiguration.RagConfiguration.class);

    @Test
    public void ragIsDisabledByDefault() {
        contextRunner.run(context -> {
            assertTrue(context.getBeansOfType(RagIngestionService.class).isEmpty());
            assertTrue(context.getBeansOfType(RagRetrievalService.class).isEmpty());
        });
    }

    @Test
    public void ragRegistersDefaultBeansWhenEnabled() throws Exception {
        File root = temporaryFolder.newFolder("default-rag");
        contextRunner
                .withPropertyValues(
                        "agent.rag.enabled=true",
                        "agent.rag.local.dir=" + root.getAbsolutePath())
                .run(context -> {
                    assertFalse(context.getBeansOfType(RagIngestionService.class).isEmpty());
                    assertFalse(context.getBeansOfType(RagRetrievalService.class).isEmpty());
                    assertEquals(1, context.getBeansOfType(OpenAiRagEmbeddingGateway.class).size());
                });
    }

    @Test
    public void customEmbeddingGatewayReplacesDefault() throws Exception {
        File root = temporaryFolder.newFolder("custom-rag");
        contextRunner
                .withBean(RagEmbeddingGateway.class, TestEmbeddingGateway::new)
                .withPropertyValues(
                        "agent.rag.enabled=true",
                        "agent.rag.local.dir=" + root.getAbsolutePath())
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(RagEmbeddingGateway.class).size());
                    assertEquals(TestEmbeddingGateway.class,
                            context.getBean(RagEmbeddingGateway.class).getClass());
                    assertTrue(context.getBeansOfType(OpenAiRagEmbeddingGateway.class).isEmpty());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentProperties.class)
    static class BaseConfiguration {

        @Bean
        RestTemplate restTemplate() {
            return new RestTemplate();
        }
    }

    private static final class TestEmbeddingGateway implements RagEmbeddingGateway {

        @Override
        public float[] embed(String text) {
            return new float[] {1F};
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            List<float[]> vectors = new ArrayList<>();
            for (String ignored : texts) {
                vectors.add(new float[] {1F});
            }
            return vectors;
        }

        @Override
        public String modelId() {
            return "test";
        }

        @Override
        public int dimensions() {
            return 1;
        }
    }
}
