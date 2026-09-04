package com.mwb.ai.claw.agent;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.dto.ApprovalCmd;
import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.dto.data.PendingApprovalDTO;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.approval.ApprovalRegistry;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.approval.PendingApproval;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.TodoDefinition;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.OrchestrationRun;
import com.mwb.ai.claw.infrastructure.collaboration.delegate.run.OrchestrationRunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 人工审批应用服务（P1 交互与上下文）：
 * 查询待审批节点（pendingTasks）、审批通过（approve）/ 拒绝（reject）。
 * <p>
 * 编排线程在命中审批门禁的层暂停等待，本服务将决策写入 {@link ApprovalRegistry} 唤醒其继续；
 * 定位键为 {@code {sessionId}/{layerKey}}（根层 layerKey=root，子层为 todoId 路径如 t1/t1-1）。
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    @Resource
    private ApprovalRegistry approvalRegistry;

    /** 编排运行记录存储（H1-P1 可中断恢复）：store=none 时无 Bean，审批决策只写内存注册表 */
    @Resource
    private ObjectProvider<OrchestrationRunStore> runStoreProvider;

    /** 列出待审批节点（可按会话过滤；空=全部；仅当前请求 scope 维度下的节点） */
    public SingleResponse<List<PendingApprovalDTO>> pendingTasks(String sessionId) {
        List<PendingApprovalDTO> result = new ArrayList<>();
        AgentScope scope = AgentScopeContext.get();
        for (PendingApproval pa : approvalRegistry.listPending(scope, sessionId)) {
            result.add(toDTO(pa));
        }
        // 启用运行持久化的挂起 run（根层门禁）并入待审批列表
        OrchestrationRunStore store = runStoreProvider.getIfAvailable();
        if (store != null) {
            for (OrchestrationRun run : store.listGated(scope, sessionId == null ? "" : sessionId)) {
                result.add(toDTO(run));
            }
        }
        return SingleResponse.of(result);
    }

    /** 审批通过：该层计划继续委派执行 */
    public SingleResponse<Void> approve(ApprovalCmd cmd) {
        return decide(cmd, true);
    }

    /** 审批拒绝：该层降级直执行（不再委派） */
    public SingleResponse<Void> reject(ApprovalCmd cmd) {
        return decide(cmd, false);
    }

    private SingleResponse<Void> decide(ApprovalCmd cmd, boolean approved) {
        if (cmd == null || cmd.getLayerKey() == null || cmd.getLayerKey().trim().isEmpty()) {
            return SingleResponse.buildFailure(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "layerKey 不能为空");
        }
        String sessionId = cmd.getSessionId() == null ? "" : cmd.getSessionId();
        AgentScope scope = AgentScopeContext.get();
        String layerKey = cmd.getLayerKey().trim();
        boolean done = approved
                ? approvalRegistry.approve(scope, sessionId, layerKey)
                : approvalRegistry.reject(scope, sessionId, layerKey);
        // 内存注册表无此节点且运行持久化已启用 → 落到挂起的 run（根层门禁）持久化解锁
        if (!done) {
            OrchestrationRunStore store = runStoreProvider.getIfAvailable();
            if (store != null) {
                java.util.Optional<OrchestrationRun> opt = store.findGated(scope, sessionId, layerKey);
                if (opt.isPresent()) {
                    OrchestrationRun run = opt.get();
                    run.setGateDecision(approved ? OrchestrationRun.DECISION_APPROVED
                            : OrchestrationRun.DECISION_REJECTED);
                    store.update(run);
                    done = true;
                }
            }
        }
        if (!done) {
            return SingleResponse.buildFailure(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "待审批节点不存在或已处理: " + sessionId + "/" + layerKey);
        }
        log.info("审批决策完成: action={}, session={}, layer={}", approved ? "approve" : "reject",
                sessionId, layerKey);
        return SingleResponse.buildSuccess();
    }

    private PendingApprovalDTO toDTO(PendingApproval pa) {
        PendingApprovalDTO dto = new PendingApprovalDTO();
        dto.setSessionId(pa.getSessionId());
        dto.setLayerKey(pa.getLayerKey());
        dto.setTask(pa.getTask());
        dto.setTodoCount(pa.getPlan().size());
        List<String> titles = new ArrayList<>();
        for (TodoDefinition t : pa.getPlan()) {
            titles.add(t.getTitle() == null ? t.getTodoId() : t.getTitle());
        }
        dto.setTodoTitles(titles);
        dto.setCreatedAt(pa.getCreatedAt());
        return dto;
    }

    /** 运行持久化的挂起 run → 待审批 DTO（任务取 run 根任务，plan 取根层 plan 快照） */
    private PendingApprovalDTO toDTO(OrchestrationRun run) {
        PendingApprovalDTO dto = new PendingApprovalDTO();
        dto.setSessionId(run.getSessionId());
        dto.setLayerKey(run.getGateLayer() == null ? "root" : run.getGateLayer());
        dto.setTask(run.getTask());
        dto.setTodoCount(0);
        dto.setTodoTitles(new ArrayList<>());
        dto.setCreatedAt(run.getCreateTime());
        if (run.getPlanJson() != null && !run.getPlanJson().isEmpty()) {
            List<String> titles = new ArrayList<>();
            try {
                for (TodoDefinition t : com.mwb.ai.claw.domain.util.JsonUtils
                        .fromJsonList(run.getPlanJson(), TodoDefinition.class)) {
                    titles.add(t.getTitle() == null ? t.getTodoId() : t.getTitle());
                }
            } catch (Exception ignore) {
                // 快照解析失败则仅展示任务描述
            }
            dto.setTodoTitles(titles);
            dto.setTodoCount(titles.size());
        }
        return dto;
    }
}
