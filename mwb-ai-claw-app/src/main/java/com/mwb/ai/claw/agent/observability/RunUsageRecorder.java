package com.mwb.ai.claw.agent.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.observability.RunUsage;
import com.mwb.ai.claw.domain.observability.RunUsageStore;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.observability.LocalRunUsageStore;

/**
 * 每次运行用量记录器（门面）：一次 Agent 执行结束后记录一条 JSONL/DB 运行摘要。
 * <p>
 * 实际持久化委托 {@link RunUsageStore}（local 本地 JSONL | db 落 {@code claw_run_usage} 表，
 * 由 {@code agent.observability.run-usage-store} 切换）；{@code agent.observability.run-usage-log=false} 关闭。
 */
@Component
public class RunUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(RunUsageRecorder.class);

    private final AgentProperties properties;

    @Resource
    private ObjectProvider<RunUsageStore> storeProvider;

    /** 非 Spring 场景（如纯 `new` 构造）兜底本地存储，保证不因注入缺失而空指针 */
    private LocalRunUsageStore fallbackStore;

    public RunUsageRecorder(AgentProperties properties) {
        this.properties = properties;
    }

    /** 解析当前存储：优先注入的 provider（可切 db），未注入时回退本地 JSONL */
    private RunUsageStore resolveStore() {
        if (storeProvider != null) {
            RunUsageStore store = storeProvider.getIfAvailable();
            if (store != null) {
                return store;
            }
        }
        if (fallbackStore == null) {
            fallbackStore = new LocalRunUsageStore(properties);
        }
        return fallbackStore;
    }

    /**
     * 记录一次运行摘要。开关关闭或 IO 失败时静默降级，不影响主链路。
     */
    public void record(RunUsage usage) {
        if (usage == null || !properties.getObservability().isRunUsageLog()) {
            return;
        }
        try {
            resolveStore().save(usage);
        } catch (Exception e) {
            log.warn("记录运行用量失败: {}", e.getMessage());
        }
    }

    /**
     * 读取指定日期（yyyy-MM-dd，空=今天）的运行记录，按时间升序返回（供 shell /runs 面板查询）。
     * IO 异常时返回空列表。
     */
    public List<Map<String, Object>> readRuns(String date) {
        try {
            return resolveStore().findByDate(date);
        } catch (Exception e) {
            log.warn("读取运行用量失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}