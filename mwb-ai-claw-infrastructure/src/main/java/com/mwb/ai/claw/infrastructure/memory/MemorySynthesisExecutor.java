package com.mwb.ai.claw.infrastructure.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记忆提炼异步执行器：单线程串行队列执行摘要/事实提炼任务，不阻塞主对话链路。
 * <p>
 * 必须串行：lastSummarized 边界依赖摘要页按序落盘，事实读写（delete+append）需原子执行。
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
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    /**
     * 提交提炼任务（串行执行）。任务内部异常由执行器捕获记录，不影响后续任务。
     */
    public void submit(String taskName, Runnable task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.warn("记忆提炼任务 {} 执行失败: {}", taskName, e.getMessage());
            }
        });
    }

    /** 当前排队/执行中的任务数（诊断用） */
    public int pendingCount() {
        return ((java.util.concurrent.ThreadPoolExecutor) executor).getQueue().size();
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
