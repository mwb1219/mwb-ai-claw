package com.mwb.ai.claw.infrastructure.rag.store;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.model.RagIndexEntry;
import com.mwb.ai.claw.domain.rag.model.RagSearchResult;
import com.mwb.ai.claw.domain.rag.model.RagVectorQuery;

/**
 * 本地 RAG 索引并发一致性测试（T8）：
 * 多线程并发写入不同文档不丢失、并发覆盖同一文档最终态一致、并发重建下检索始终读到一致视图。
 */
public class LocalRagIndexStoreConcurrencyTest {

    private static final float[] V = new float[] {1F, 0F, 0F};

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private LocalRagIndexStore store;

    @Before
    public void setUp() throws Exception {
        RagConfig config = new RagConfig();
        config.getLocal().setDir(tmp.newFolder("rag").getAbsolutePath());
        store = new LocalRagIndexStore(config);
        store.init();
    }

    @Test
    public void concurrentUpserts_differentDocuments_noDataLoss() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {
                    }
                    store.upsert(Collections.singletonList(
                            entry("kb", "doc-" + idx, "chunk-" + idx, "content-" + idx)));
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        List<RagSearchResult> results = search("kb", V, 100);
        assertEquals("并发写入不同文档不应丢失任何一条", threads, results.size());
    }

    @Test
    public void concurrentUpserts_sameDocument_consistentLastWriteWins() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.execute(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {
                    }
                    // 同一 documentId 并发覆盖：replaceDocuments 先删旧块再写新块，最终只保留一个块
                    store.upsert(Collections.singletonList(
                            entry("kb", "doc-x", "chunk-" + idx, "content-" + idx)));
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        List<RagSearchResult> results = search("kb", V, 10);
        assertEquals("同一文档并发覆盖后应保留且仅保留一个版本块", 1, results.size());
        RagSearchResult r = results.get(0);
        assertEquals("doc-x", r.getDocumentId());
        // 内容与 chunkId 命名一致，说明未发生块字段错乱/张冠李戴
        assertEquals(r.getChunkId().replace("chunk-", "content-"), r.getContent());
    }

    @Test
    public void concurrentRebuild_searchAlwaysSeesConsistentView() throws Exception {
        store.upsert(Collections.singletonList(entry("kb", "doc-r", "chunk-old", "old-content")));

        int writers = 2;
        int rebuildCycles = 5;
        int readers = 4;
        int readsPerReader = 20;
        AtomicBoolean corrupt = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        try {
            for (int w = 0; w < writers; w++) {
                final int wid = w;
                pool.execute(() -> {
                    for (int i = 0; i < rebuildCycles; i++) {
                        store.deleteByDocument("kb", "doc-r");
                        store.upsert(Collections.singletonList(
                                entry("kb", "doc-r", "chunk-" + wid + "-" + i,
                                        "content-" + wid + "-" + i)));
                    }
                });
            }
            for (int r = 0; r < readers; r++) {
                pool.execute(() -> {
                    for (int i = 0; i < readsPerReader; i++) {
                        for (RagSearchResult res : search("kb", V, 10)) {
                            if ("doc-r".equals(res.getDocumentId())
                                    && (res.getChunkId() == null || res.getChunkId().isEmpty()
                                    || res.getContent() == null || res.getContent().isEmpty())) {
                                corrupt.set(true);
                            }
                        }
                    }
                });
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertFalse("并发重建下检索不应读到字段损坏的分块", corrupt.get());
    }

    private RagIndexEntry entry(String kb, String doc, String chunkId, String content) {
        RagIndexEntry e = new RagIndexEntry();
        e.setKnowledgeBaseId(kb);
        e.setDocumentId(doc);
        e.setDocumentVersion(1L);
        e.setChunkId(chunkId);
        e.setSequence(0);
        e.setContent(content);
        e.setVector(V);
        e.setEmbeddingModel("test-embedding");
        e.setDimensions(3);
        return e;
    }

    private List<RagSearchResult> search(String kb, float[] vector, int topK) {
        RagVectorQuery q = new RagVectorQuery();
        q.setKnowledgeBaseIds(Collections.singletonList(kb));
        q.setVector(vector);
        q.setDimensions(3);
        q.setTopK(topK);
        q.setMinScore(0D);
        return store.search(q);
    }
}