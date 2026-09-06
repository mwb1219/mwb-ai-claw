package com.mwb.ai.claw.infrastructure.collaboration.delegate.run;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.lock.DistributedLock;
import com.mwb.ai.claw.infrastructure.lock.LockOptions;
import com.mwb.ai.claw.infrastructure.lock.LockResult;

/**
 * H1-P1 切片 2：悬挂编排运行记录清理定时任务（仅 {@code store} ∈ {file, local, db} 形态装配）。
 * <p>
 * 职责：按 {@code cleanupIntervalHours} 周期，把超过 {@code staleTtlMs} 且 phase 仍为
 * GATE / SUSPENDED / RUNNING 的 run 从 {@link OrchestrationRunStore} 清除（跨会话中断 / 实例崩溃残留的悬挂 run）。
 * <ul>
 *   <li>总开关：{@code agent.collaboration.orchestration-run.cleanup-enabled}（默认 true，false 时静默不启动）；</li>
 *   <li>跨实例互斥：多实例用可选 {@link DistributedLock} 抢锁（fail-open，无 Redis 时本机直接执行）；</li>
 *   <li>GATE 记录被清理后，审批侧 findGated 定位不到即按「已失效」放行，不会重新续跑。 </li>
 * </ul>
 */
public class OrchestrationRunCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationRunCleanupScheduler.class);

    /** 跨实例全局互斥的清理锁 key（清理无租户维度，全局一把锁）。 */
    private static final String CLEANUP_LOCK_KEY = "claw:orchestration-run:cleanup";

    private static final Duration CLEANUP_LOCK_TTL = Duration.ofMinutes(10);

    private final OrchestrationRunStore store;
    private final AgentProperties.OrchestrationRunConfig config;
    private final DistributedLock distributedLock;

    private volatile ScheduledExecutorService scheduler;

    public OrchestrationRunCleanupScheduler(OrchestrationRunStore store,
                                            AgentProperties.OrchestrationRunConfig config,
                                            DistributedLock distributedLock) {
        this.store = store;
        this.config = config;
        // 分布式锁为可选：classpath 无 spring-data-redis 或未启用 Redis 锁形态时保持本机执行（单实例）。
        this.distributedLock = distributedLock;
        if (config.isCleanupEnabled()) {
            start();
        } else {
            log.info("悬挂编排运行记录清理未启用（cleanup-enabled=false），跳过定时任务");
        }
    }

    private void start() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "orchestration-run-cleanup");
            t.setDaemon(true);
            return t;
        });
        long intervalHours = Math.max(1, config.getCleanupIntervalHours());
        // 首次执行延迟一个周期（避免应用启动时立即清库），随后按间隔周期调度
        scheduler.scheduleWithFixedDelay(this::runCleanup, intervalHours, intervalHours, TimeUnit.HOURS);
        log.info("悬挂编排运行记录清理定时任务已启动: interval={}h, staleTtlMs={}", intervalHours, config.getStaleTtlMs());
    }

    /** 单次清理执行体：多实例下先抢分布式锁，仅持有者执行扫描删除。 */
    public void runCleanup() {
        if (distributedLock == null) {
            doCleanup();
            return;
        }
        try {
            LockResult<Void> result = distributedLock.execute(CLEANUP_LOCK_KEY,
                    LockOptions.tryLockWithRenew(CLEANUP_LOCK_TTL, Duration.ZERO),
                    this::doCleanup);
            if (!result.isAcquired()) {
                // tryLock 不等待：被其他实例持有则本轮直接跳过，等待下个调度周期再尝试
                log.info("悬挂编排运行记录清理已被其他实例执行，本轮跳过: key={}", CLEANUP_LOCK_KEY);
            }
        } catch (Exception e) {
            // 抢锁异常（如 Redis 抖动）：fail-open 跳过本轮，避免阻塞调度线程，下周期重试
            log.warn("悬挂编排运行记录清理获取分布式锁异常，本轮跳过: {}", e.getMessage());
        }
    }

    /** 实际清理动作（被分布式锁或本机直接调用）。 */
    private Void doCleanup() {
        try {
            long cutoff = System.currentTimeMillis() - Math.max(0, config.getStaleTtlMs());
            int removed = store.deleteStale(cutoff, Integer.MAX_VALUE);
            if (removed > 0) {
                log.info("悬挂编排运行记录清理完成: 删除 {} 条, cutoff={}ms", removed, cutoff);
            }
        } catch (Exception e) {
            log.warn("悬挂编排运行记录清理失败，将在下一周期重试: {}", e.getMessage());
        }
        return null;
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