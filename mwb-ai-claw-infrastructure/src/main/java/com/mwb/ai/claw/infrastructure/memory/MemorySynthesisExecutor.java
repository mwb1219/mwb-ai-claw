package com.mwb.ai.claw.infrastructure.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记忆提炼异步执行器：单线程串行队列执行摘要/事实提炼任务，不阻塞主对话链路。
 * <p>
 * 必须串行：lastSummarized 边界依赖摘要页按序落盘，事实读写（delete+append）需原子执行。
 * <p>
 * Phase 4 调度优化：同类型待执行任务去重——提交新任务时若队列中已有同名的
 * pending 任务（如同会话的 afterTurn），移除旧任务只保留最新。因为提炼边界
 * （lastSummarized/lastArchived）在执行时从磁盘读取，新任务会覆盖旧任务的处理范围，
 * 丢弃旧任务不会丢失提炼内容，还能避免无效 LLM 调用（成本优化）。
 */
@Component
public class MemorySynthesisExecutor {

    private static final Logger log = LoggerFactory.getLogger(MemorySynthesisExecutor.class);

    private final ExecutorService executor;

    public MemorySynthesisExecutor() {
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "memory-synth-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
        // 直接构造 ThreadPoolExecutor（而非 Executors.newSingleThreadExecutor），
        // 便于通过 getQueue() 做同会话任务去重与 pendingCount 诊断
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), factory);
    }

    /**
     * 提交提炼任务（串行执行）。任务内部异常由执行器捕获记录，不影响后续任务。
     * 队列中已有的同名任务会被移除（只保留最新）。
     */
    public void submit(String taskName, Runnable task) {
        ThreadPoolExecutor pool = (ThreadPoolExecutor) executor;
        // 移除队列中同名的旧任务（执行中的任务无法取消，只处理待执行的）
        pool.getQueue().removeIf(r -> r instanceof NamedTask
                && taskName.equals(((NamedTask) r).name));
        executor.submit(new NamedTask(taskName, task));
    }

    /** 当前排队/执行中的任务数（诊断用） */
    public int pendingCount() {
        return ((java.util.concurrent.ThreadPoolExecutor) executor).getQueue().size();
    }

    /** 提炼任务包装：携带任务名，用于同会话任务去重 */
    private static class NamedTask implements Runnable {
        final String name;
        final Runnable delegate;

        NamedTask(String name, Runnable delegate) {
            this.name = name;
            this.delegate = delegate;
        }

        @Override
        public void run() {
            try {
                delegate.run();
            } catch (Exception e) {
                log.warn("记忆提炼任务 {} 执行失败: {}", name, e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
