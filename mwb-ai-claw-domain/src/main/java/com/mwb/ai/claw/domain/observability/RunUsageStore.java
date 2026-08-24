package com.mwb.ai.claw.domain.observability;

import java.util.List;
import java.util.Map;

/**
 * 运行用量摘要存储 SPI：保存 / 按日期查询一次运行摘要。
 * <p>
 * 框架提供两套实现（见自动装配）：
 * <ul>
 *   <li>{@code LocalRunUsageStore}（{@code agent.observability.run-usage-store=local}，默认）：JSONL 逐行追加；</li>
 *   <li>{@code JdbcRunUsageStore}（{@code agent.observability.run-usage-store=db}）：落 {@code claw_run_usage} 表，
 *      多实例共享，适合生产环境。</li>
 * </ul>
 * 使用方可用 {@code @Bean}（{@code @ConditionalOnMissingBean} 覆盖）替换为任意后端。
 */
public interface RunUsageStore {

    /** 保存一次运行摘要（local 逐行追加 JSONL / db 插入一行） */
    void save(RunUsage usage);

    /**
     * 按日期（yyyy-MM-dd，空=today）查询运行摘要，按时间升序返回。
     * 每项为展示字段 Map（含 ts），供 shell /runs 面板渲染；无记录返回空列表。
     */
    List<Map<String, Object>> findByDate(String date);
}