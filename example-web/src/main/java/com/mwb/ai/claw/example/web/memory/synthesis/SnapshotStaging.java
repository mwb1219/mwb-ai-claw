package com.mwb.ai.claw.example.web.memory.synthesis;

import java.util.List;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesisDispatcher.Kind;
import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 提炼任务快照暂存 SPI（Phase 3 MQ staging）。
 * <p>
 * Phase 3 {@code RocketMqMemorySynthesisDispatcher} 在 produce 时将快照暂存到 DB（避免 MQ 消息体过大），
 * 消费端从 staging 取回快照后执行提炼。默认实现为 JDBC（{@code claw_memory_snapshot} 表）。
 *
 * @see com.mwb.ai.claw.example.web.memory.synthesis.JdbcSnapshotStaging
 */
public interface SnapshotStaging {

    /**
     * 暂存快照并返回版本号。
     * <p>
     * 若同 scope+sessionId+kind+version 的快照已存在（唯一键冲突），忽略写入直接返回 version（天然去重）。
     *
     * @return 快照版本号（epoch 毫秒时间戳）
     */
    long save(AgentScope scope, String sessionId, Kind kind, List<Message> snapshot);

    /**
     * 按版本号加载快照。消费端拿到 version 后从 staging 取完整快照。
     *
     * @return 快照列表；不存在返回 {@code null}
     */
    List<Message> load(AgentScope scope, String sessionId, Kind kind, long version);

    /**
     * 清理已消费的快照。
     */
    void delete(AgentScope scope, String sessionId, Kind kind, long version);

    /**
     * 清理过期快照（保留超过 TTL 未被消费的，例如 MQ 消息丢失或消费失败长期滞留）。
     *
     * @param ttlMillis 保留时间（毫秒）
     * @return 清理条数
     */
    int cleanupExpired(long ttlMillis);
}
