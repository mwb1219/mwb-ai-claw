package com.mwb.ai.claw.infrastructure.memory.storage;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.memory.layered.LayeredMemoryConfig;
import com.mwb.ai.claw.infrastructure.memory.storage.jdbc.JdbcMemoryPageStore;
import com.mwb.ai.claw.infrastructure.memory.storage.redis.RedisMemoryIndexer;

/**
 * T6：记忆页过期清理定时任务（仅 {@code agent.storage.type=db} 形态装配）。
 * <p>
 * 职责：按 {@code cleanupIntervalHours} 周期，把超过 {@code cleanupOlderThanDays} 天未更新的
 * SUMMARY / ARCHIVE 记忆页与 FACT 事实页从 MySQL 权威库删除，并同步失效 Redis 派生检索索引。
 * <ul>
 *   <li>cleaning 总开关：{@code agent.memory.cleanup-enabled}（默认 true，false 时本任务静默不启动）；</li>
 *   <li>跨 scope 全局执行：已归档/关闭会话的记忆页与事实页无租户维度，一次性全量清理；</li>
 *   <li>Redis fail-open：无 spring-data-redis 或清理索引失败不阻断 DB 主清理（降级为索引残留，靠重建自愈）。</li>
 * </ul>
 * file 存储形态不装配此 Bean：本地文件由文件系统自身保留，不做定时清理。
 */
public class MemoryPageCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryPageCleanupScheduler.class);
    private static final long HOUR_MILLIS = 60 * 60 * 1000L;

    private final JdbcMemoryPageStore pageStore;
    private final RedisMemoryIndexer indexer;
    private final LayeredMemoryConfig config;

    private volatile ScheduledExecutorService scheduler;

    public MemoryPageCleanupScheduler(JdbcMemoryPageStore pageStore,
                                      RedisMemoryIndexer indexer,
                                      LayeredMemoryConfig config) {
        this.pageStore = pageStore;
        this.indexer = indexer;
        this.config = config;
        if (config.isCleanupEnabled()) {
            start();
        } else {
            log.info("记忆页过期清理未启用（agent.memory.cleanup-enabled=false），跳过定时任务");
        }
    }

    private void start() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "memory-page-cleanup");
            t.setDaemon(true);
            return t;
        });
        long intervalHours = Math.max(1, config.getCleanupIntervalHours());
        // 首次执行延迟一个周期（避免应用启动时立即触发清库），随后按间隔周期调度
        scheduler.scheduleWithFixedDelay(this::runCleanup,
                intervalHours, intervalHours, TimeUnit.HOURS);
        log.info("记忆页过期清理定时任务已启动: interval={}h, olderThanDays={}",
                intervalHours, config.getCleanupOlderThanDays());
    }

    /** 单次清理执行体：DB 权威删除 + Redis 派生索引失效。 */
    public void runCleanup() {
        try {
            long cutoff = System.currentTimeMillis()
                    - Math.max(0, config.getCleanupOlderThanDays()) * 24 * HOUR_MILLIS;
            int pages = pageStore.deleteSummaryArchiveOlderThan(cutoff);
            int facts = pageStore.deleteFactsOlderThan(cutoff);
            if (indexer != null) {
                indexer.cleanExpired(cutoff);
            }
            log.info("记忆页过期清理完成: 记忆页={} 条, 事实页={} 条, cutoff={}ms（全局）", pages, facts, cutoff);
        } catch (Exception e) {
            log.warn("记忆页过期清理失败，将在下一周期重试: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        ScheduledExecutorService s = this.scheduler;
        if (s != null) {
            s.shutdownNow();
            this.scheduler = null;
        }
    }
}