package com.mwb.ai.claw.example.web.memory.synthesis;

/**
 * Phase 3 MQ 提炼任务消息体：只携带元数据，完整快照存 staging 表（避免 MQ 消息体过大）。
 * <p>
 * 字段命名保持小写字段风格，兼容 Jackson 默认序列化。
 */
public class SynthTaskMessage {

    private String tenantId;
    private String userId;
    private String sessionId;
    /** AFTER_TURN / AFTER_SESSION */
    private String kind;
    /** staging 中的快照版本号 */
    private long snapshotVersion;
    /** produce 时间戳（epoch 毫秒），用于诊断消费延迟 */
    private long produceTime;

    public SynthTaskMessage() {
    }

    public SynthTaskMessage(String tenantId, String userId, String sessionId,
                            String kind, long snapshotVersion) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.sessionId = sessionId;
        this.kind = kind;
        this.snapshotVersion = snapshotVersion;
        this.produceTime = System.currentTimeMillis();
    }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public long getSnapshotVersion() { return snapshotVersion; }
    public void setSnapshotVersion(long snapshotVersion) { this.snapshotVersion = snapshotVersion; }

    public long getProduceTime() { return produceTime; }
    public void setProduceTime(long produceTime) { this.produceTime = produceTime; }

    @Override
    public String toString() {
        return "SynthTaskMessage{sessionId=" + sessionId + ", kind=" + kind
                + ", version=" + snapshotVersion + "}";
    }
}
