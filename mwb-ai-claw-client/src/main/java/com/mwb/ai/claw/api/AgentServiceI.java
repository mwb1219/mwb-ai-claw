package com.mwb.ai.claw.api;

import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.CreateSessionCmd;
import com.mwb.ai.claw.dto.UpdateSessionCmd;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import com.mwb.ai.claw.dto.data.SessionDTO;

import java.util.List;

/**
 * Agent 服务接口
 */
public interface AgentServiceI {

    /**
     * 与 Agent 对话：执行 ReAct 推理循环并返回最终回复
     * @param cmd 对话命令
     */
    SingleResponse<ChatResponseDTO> chat(ChatCmd cmd);

    /**
     * 与Agent对话：执行ReAct推理，进行流式返回，包含进度和LLM流式回调
     * @param cmd 对话命令
     * @param progressCallback 进度回调
     * @param llmStreamCallback LLM流式回调
     */
    SingleResponse<ChatResponseDTO> chat(ChatCmd cmd, ProgressCallback progressCallback, LlmStreamCallback llmStreamCallback);

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

    /**
     * 更新会话（当前支持修改标题）
     */
    SingleResponse<SessionDTO> updateSession(String sessionId, UpdateSessionCmd cmd);

    /**
     * 复制会话（新会话 id，标题加「副本」后缀）
     */
    SingleResponse<SessionDTO> duplicateSession(String sessionId);
}
