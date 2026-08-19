package com.mwb.ai.claw.infrastructure.collaboration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待审批注册表（P1 交互与上下文）：内存登记命中审批门禁的编排节点，供审批 API 定位与决策。
 * <p>
 * 编排线程注册后阻塞等待；REST / WebSocket 审批接口按 {@code {sessionId}/{layerKey}} 定位节点
 * 并写入 approve / reject 决策唤醒编排线程。节点决策后自动从注册表移除（幂等）。
 */
@Component
public class ApprovalRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRegistry.class);

    /** 注册键分隔符（层级路径可能含 '/'，故用 '#' 分隔 sessionId 与 layerKey） */
    private static final String KEY_SEP = "#";

    private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();

    /**
     * 注册一个等待审批的节点，返回其引用（编排线程用它 await 决策）。
     */
    public PendingApproval register(String sessionId, String layerKey, String task,
                                    List<TodoDefinition> plan) {
        PendingApproval pa = new PendingApproval(sessionId, layerKey, task, plan);
        pending.put(key(sessionId, layerKey), pa);
        log.info("审批门禁: 登记待审批节点 session={}, layer={}, todos={}",
                sessionId, layerKey, plan.size());
        return pa;
    }

    /** 列出全部待审批节点（可按会话过滤；空=全部） */
    public List<PendingApproval> listPending(String sessionId) {
        List<PendingApproval> result = new ArrayList<>();
        for (PendingApproval pa : pending.values()) {
            if (sessionId == null || sessionId.trim().isEmpty()
                    || sessionId.equals(pa.getSessionId())) {
                result.add(pa);
            }
        }
        result.sort((a, b) -> Long.compare(a.getCreatedAt(), b.getCreatedAt()));
        return result;
    }

    /**
     * 审批通过：唤醒对应节点继续委派执行。
     *
     * @return true=存在该节点且已决策；false=节点不存在或已决策
     */
    public boolean approve(String sessionId, String layerKey) {
        return decide(sessionId, layerKey, ApprovalDecision.APPROVED);
    }

    /**
     * 审批拒绝：唤醒对应节点，该层降级直执行。
     *
     * @return true=存在该节点且已决策；false=节点不存在或已决策
     */
    public boolean reject(String sessionId, String layerKey) {
        return decide(sessionId, layerKey, ApprovalDecision.REJECTED);
    }

    private boolean decide(String sessionId, String layerKey, ApprovalDecision decision) {
        String k = key(sessionId, layerKey);
        PendingApproval pa = pending.get(k);
        if (pa == null) {
            log.warn("审批门禁: 待审批节点不存在或已完成: session={}, layer={}", sessionId, layerKey);
            return false;
        }
        pa.decide(decision);
        pending.remove(k, pa); // 已决策即移除，幂等
        log.info("审批门禁: 节点已决策 {}: session={}, layer={}", decision, sessionId, layerKey);
        return true;
    }

    /**
     * 移除指定节点（等待超时自动降级时由编排线程调用，避免已超时节点残留在待审批列表）。
     */
    public void remove(PendingApproval pa) {
        if (pa == null) {
            return;
        }
        pending.remove(key(pa.getSessionId(), pa.getLayerKey()), pa);
    }

    private String key(String sessionId, String layerKey) {
        return (sessionId == null ? "" : sessionId) + KEY_SEP + (layerKey == null ? "" : layerKey);
    }
}
