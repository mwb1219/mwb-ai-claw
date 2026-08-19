package com.mwb.ai.claw.web;

import com.mwb.ai.claw.agent.ApprovalService;
import com.mwb.ai.claw.dto.ApprovalCmd;
import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.dto.data.PendingApprovalDTO;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * 人工审批 REST 接口（P1 交互与上下文）：查询待审批节点并对 delegate 编排的暂停层做出决策。
 * <ul>
 *   <li>{@code GET /agent/pending-tasks?sessionId=}：待审批节点列表（可按会话过滤）；</li>
 *   <li>{@code POST /agent/approve}：审批通过，该层计划继续委派执行；</li>
 *   <li>{@code POST /agent/reject}：审批拒绝，该层降级直执行。</li>
 * </ul>
 */
@RestController
@RequestMapping("/agent")
@Profile("web")
public class ApprovalController {

    @Resource
    private ApprovalService approvalService;

    @GetMapping("/pending-tasks")
    public SingleResponse<List<PendingApprovalDTO>> pendingTasks(
            @RequestParam(required = false) String sessionId) {
        return approvalService.pendingTasks(sessionId);
    }

    @PostMapping("/approve")
    public SingleResponse<Void> approve(@RequestBody ApprovalCmd cmd) {
        return approvalService.approve(cmd);
    }

    @PostMapping("/reject")
    public SingleResponse<Void> reject(@RequestBody ApprovalCmd cmd) {
        return approvalService.reject(cmd);
    }
}
