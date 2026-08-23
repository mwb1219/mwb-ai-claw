package com.mwb.ai.claw.infrastructure.rag;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.embed.RagEmbeddingGateway;
import com.mwb.ai.claw.domain.rag.model.RagDocument;
import com.mwb.ai.claw.domain.rag.model.RagIndexEntry;
import com.mwb.ai.claw.domain.rag.model.RagIngestionCommand;
import com.mwb.ai.claw.domain.rag.model.RagIngestionResult;
import com.mwb.ai.claw.domain.rag.model.RagQuery;
import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.infrastructure.rag.retrieve.DefaultRagRetrievalService;
import com.mwb.ai.claw.infrastructure.rag.store.FileRagDocumentStore;
import com.mwb.ai.claw.infrastructure.rag.store.LocalRagIndexStore;
import com.mwb.ai.claw.infrastructure.rag.write.DefaultRagIngestionService;
import com.mwb.ai.claw.infrastructure.rag.write.TextRagChunker;
import com.mwb.ai.claw.infrastructure.rag.write.TextRagDocumentParser;

/**
 * 独立 RAG 本地写入与检索链路测试。
 */
public class RagPipelineTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private DeterministicEmbeddingGateway embeddingGateway;
    private FileRagDocumentStore documentStore;
    private LocalRagIndexStore indexStore;
    private DefaultRagIngestionService ingestionService;
    private DefaultRagRetrievalService retrievalService;

    @Before
    public void setUp() throws Exception {
        File root = temporaryFolder.newFolder("rag");
        RagConfig config = new RagConfig();
        config.getLocal().setDir(root.getAbsolutePath());
        config.getIngestion().setChunkSize(1000);
        config.getIngestion().setChunkOverlap(0);
        config.getIngestion().setEmbeddingBatchSize(2);
        config.getRetrieval().setTopK(10);
        config.getRetrieval().setMinScore(0.8D);

        embeddingGateway = new DeterministicEmbeddingGateway();
        documentStore = new FileRagDocumentStore(config);
        indexStore = new LocalRagIndexStore(config);
        documentStore.init();
        indexStore.init();
        ingestionService = new DefaultRagIngestionService(
                new TextRagDocumentParser(),
                new TextRagChunker(config),
                embeddingGateway,
                indexStore,
                documentStore,
                config);
        retrievalService = new DefaultRagRetrievalService(
                embeddingGateway, indexStore, documentStore, null, config);
    }

    @Test
    public void ingestIsIdempotentAndUpdateReplacesOldVersion() {
        RagIngestionResult first = ingest("kb-main", "guide", "alpha knowledge", null);
        assertEquals(1L, first.getVersion());
        assertEquals(RagDocument.Status.READY, first.getStatus());
        assertFalse(first.isSkipped());
        assertEquals(1, search("alpha", Collections.singletonList("kb-main"), null).size());

        RagIngestionResult duplicate = ingest("kb-main", "guide", "alpha knowledge", null);
        assertTrue(duplicate.isSkipped());
        assertEquals(1L, duplicate.getVersion());

        RagIngestionResult updated = ingest("kb-main", "guide", "beta knowledge", null);
        assertEquals(2L, updated.getVersion());
        assertFalse(updated.isSkipped());
        assertTrue(search("alpha", Collections.singletonList("kb-main"), null).isEmpty());

        List<RagSearchResult> betaResults =
                search("beta", Collections.singletonList("kb-main"), null);
        assertEquals(1, betaResults.size());
        assertEquals(2L, betaResults.get(0).getDocumentVersion());

        ingestionService.deleteDocument("kb-main", "guide");
        assertTrue(search("beta", Collections.singletonList("kb-main"), null).isEmpty());
        assertTrue(documentStore.list("kb-main").isEmpty());
    }

    @Test
    public void searchSupportsAllKnowledgeBasesAndMetadataFilters() {
        Map<String, String> east = new LinkedHashMap<>();
        east.put("region", "east");
        Map<String, String> west = new LinkedHashMap<>();
        west.put("region", "west");

        ingest("kb-east", "shared-doc", "alpha east", east);
        ingest("kb-west", "shared-doc", "alpha west", west);

        List<RagSearchResult> all = search("alpha", Collections.emptyList(), null);
        assertEquals("相同 documentId/chunkId 在不同知识库中不能被误去重", 2, all.size());

        List<RagSearchResult> filtered = search("alpha", Collections.emptyList(), east);
        assertEquals(1, filtered.size());
        assertEquals("kb-east", filtered.get(0).getKnowledgeBaseId());
        assertEquals("east", filtered.get(0).getMetadata().get("region"));
    }

    @Test
    public void failedUpdateKeepsPreviousReadyVersionSearchable() {
        ingest("kb-main", "guide", "alpha stable", null);
        embeddingGateway.setFail(true);
        try {
            ingest("kb-main", "guide", "beta broken", null);
            fail("Embedding 失败时摄入必须抛出异常");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("embedding failed"));
        }

        RagDocument stored = documentStore.find("kb-main", "guide");
        assertNotNull(stored);
        assertEquals(RagDocument.Status.READY, stored.getStatus());
        assertEquals(1L, stored.getVersion());

        embeddingGateway.setFail(false);
        assertEquals(1, search("alpha", Collections.singletonList("kb-main"), null).size());
        assertTrue(search("beta", Collections.singletonList("kb-main"), null).isEmpty());
    }

    @Test
    public void firstIngestionFailureRecordsFailedStatusWithoutIndex() {
        embeddingGateway.setFail(true);
        try {
            ingest("kb-main", "broken", "alpha", null);
            fail("Embedding 失败时摄入必须抛出异常");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("embedding failed"));
        }

        RagDocument stored = documentStore.find("kb-main", "broken");
        assertNotNull(stored);
        assertEquals(RagDocument.Status.FAILED, stored.getStatus());
        embeddingGateway.setFail(false);
        assertTrue(search("alpha", Collections.singletonList("kb-main"), null).isEmpty());
    }

    @Test
    public void sameChunkIdInDifferentDocumentsIsNotDeduplicated() {
        documentStore.save(readyDocument("kb-main", "doc-a"));
        documentStore.save(readyDocument("kb-main", "doc-b"));
        indexStore.upsert(Arrays.asList(
                indexEntry("kb-main", "doc-a", "shared-chunk", "alpha from A"),
                indexEntry("kb-main", "doc-b", "shared-chunk", "alpha from B")));

        List<RagSearchResult> results =
                search("alpha", Collections.singletonList("kb-main"), null);

        assertEquals(2, results.size());
    }

    private RagIngestionResult ingest(String knowledgeBaseId,
                                      String documentId,
                                      String content,
                                      Map<String, String> metadata) {
        RagIngestionCommand command = new RagIngestionCommand();
        command.setKnowledgeBaseId(knowledgeBaseId);
        command.setDocumentId(documentId);
        command.setName(documentId + ".txt");
        command.setContentType("text/plain");
        command.setContent(content);
        if (metadata != null) {
            command.setMetadata(metadata);
        }
        return ingestionService.ingest(command);
    }

    private List<RagSearchResult> search(String text,
                                         List<String> knowledgeBaseIds,
                                         Map<String, String> filters) {
        RagQuery query = new RagQuery();
        query.setText(text);
        query.setKnowledgeBaseIds(knowledgeBaseIds);
        if (filters != null) {
            query.setFilters(filters);
        }
        return retrievalService.retrieve(query);
    }

    private RagDocument readyDocument(String knowledgeBaseId, String documentId) {
        RagDocument document = new RagDocument();
        document.setKnowledgeBaseId(knowledgeBaseId);
        document.setDocumentId(documentId);
        document.setVersion(1L);
        document.setStatus(RagDocument.Status.READY);
        return document;
    }

    private RagIndexEntry indexEntry(String knowledgeBaseId,
                                     String documentId,
                                     String chunkId,
                                     String content) {
        RagIndexEntry entry = new RagIndexEntry();
        entry.setKnowledgeBaseId(knowledgeBaseId);
        entry.setDocumentId(documentId);
        entry.setDocumentVersion(1L);
        entry.setChunkId(chunkId);
        entry.setContent(content);
        entry.setVector(new float[] {1F, 0F, 0F});
        entry.setEmbeddingModel("test-embedding");
        entry.setDimensions(3);
        return entry;
    }

    private static final class DeterministicEmbeddingGateway implements RagEmbeddingGateway {

        private boolean fail;

        @Override
        public float[] embed(String text) {
            if (fail) {
                throw new IllegalStateException("embedding failed");
            }
            return vector(text);
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            if (fail) {
                throw new IllegalStateException("embedding failed");
            }
            List<float[]> result = new ArrayList<>();
            for (String text : texts) {
                result.add(vector(text));
            }
            return result;
        }

        @Override
        public String modelId() {
            return "test-embedding";
        }

        @Override
        public int dimensions() {
            return 3;
        }

        public void setFail(boolean fail) {
            this.fail = fail;
        }

        private float[] vector(String text) {
            String normalized = text.toLowerCase(Locale.ROOT);
            if (normalized.contains("alpha")) {
                return new float[] {1F, 0F, 0F};
            }
            if (normalized.contains("beta")) {
                return new float[] {0F, 1F, 0F};
            }
            return new float[] {0F, 0F, 1F};
        }
    }
}
