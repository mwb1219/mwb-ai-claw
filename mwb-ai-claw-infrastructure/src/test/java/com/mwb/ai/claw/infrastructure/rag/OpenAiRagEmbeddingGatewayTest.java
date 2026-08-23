package com.mwb.ai.claw.infrastructure.rag;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.infrastructure.rag.embed.OpenAiRagEmbeddingGateway;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * OpenAI 兼容 Embedding 响应解析测试。
 */
public class OpenAiRagEmbeddingGatewayTest {

    @Test
    public void batchResponseIsOrderedByResponseIndex() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(requestTo("http://embedding.test/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"data\":["
                                + "{\"index\":1,\"embedding\":[0.0,1.0]},"
                                + "{\"index\":0,\"embedding\":[1.0,0.0]}]}",
                        MediaType.APPLICATION_JSON));

        RagConfig config = new RagConfig();
        config.getEmbedding().setBaseUrl("http://embedding.test/v1/");
        config.getEmbedding().setModel("test-model");
        config.getEmbedding().setApiKey("secret");
        config.getEmbedding().setDimensions(2);
        OpenAiRagEmbeddingGateway gateway =
                new OpenAiRagEmbeddingGateway(config, restTemplate);

        List<float[]> vectors = gateway.embedBatch(Arrays.asList("first", "second"));

        assertEquals(2, vectors.size());
        assertArrayEquals(new float[] {1F, 0F}, vectors.get(0), 0F);
        assertArrayEquals(new float[] {0F, 1F}, vectors.get(1), 0F);
        assertEquals(2, gateway.dimensions());
        server.verify();
    }
}
