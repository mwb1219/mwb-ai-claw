package com.mwb.ai.claw.agent;

import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.dto.ApprovalCmd;
import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.dto.data.AgentErrorCode;
import com.mwb.ai.claw.dto.data.PendingApprovalDTO;
import com.mwb.ai.claw.infrastructure.collaboration.ApprovalRegistry;
import com.mwb.ai.claw.infrastructure.collaboration.PendingApproval;
import com.mwb.ai.claw.infrastructure.collaboration.TodoDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /** 列出待审批节点（可按会话过滤；空=全部；仅当前请求 scope 维度下的节点） */
    public SingleResponse<List<PendingApprovalDTO>> pendingTasks(String sessionId) {
        List<PendingApprovalDTO> result = new ArrayList<>();
        for (PendingApproval pa : approvalRegistry.listPending(AgentScopeContext.get(), sessionId)) {
            result.add(toDTO(pa));
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
        boolean done = approved
                ? approvalRegistry.approve(scope, sessionId, cmd.getLayerKey().trim())
                : approvalRegistry.reject(scope, sessionId, cmd.getLayerKey().trim());
        if (!done) {
            return SingleResponse.buildFailure(AgentErrorCode.B_AGENT_CONFIG_ERROR.getErrCode(),
                    "待审批节点不存在或已处理: " + sessionId + "/" + cmd.getLayerKey());
        }
        log.info("审批决策完成: action={}, session={}, layer={}", approved ? "approve" : "reject",
                sessionId, cmd.getLayerKey());
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
}
