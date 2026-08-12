package com.mwb.ai.claw.api;

import com.alibaba.cola.dto.SingleResponse;
import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.CreateSessionCmd;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import com.mwb.ai.claw.dto.data.SessionDTO;

import java.util.List;

/**
 * Agent 服务接口
 */
public interface AgentServiceI {

    /**
     * 与 Agent 对话：执行 ReAct 推理循环并返回最终回复
     */
    SingleResponse<ChatResponseDTO> chat(ChatCmd cmd);

    /**
     * 创建新会话
     */
    SingleResponse<SessionDTO> createSession(CreateSessionCmd cmd);

    /**
     * 查询会话详情（含历史消息）
     */
    SingleResponse<SessionDTO> getSession(String sessionId);

    /**
     * 列出所有会话（含轻量元数据）
     */
    SingleResponse<List<SessionDTO>> listSessions();

    /**
     * 删除指定会话
     */
    SingleResponse<Void> deleteSession(String sessionId);
}
