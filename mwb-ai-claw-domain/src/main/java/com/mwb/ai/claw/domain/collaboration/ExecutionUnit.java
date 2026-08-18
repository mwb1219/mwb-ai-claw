package com.mwb.ai.claw.domain.collaboration;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.core.ReActResult;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;

import java.nio.file.Path;

/**
 * 公共执行单元：编排器复用的执行原语（ReActLoopService + 会话 + 记忆 + 产物落盘）。
 * <p>
 * 由 infrastructure 层实现，封装：
 * - 主会话的获取/创建与 ReAct 执行（routing 使用）；
 * - 临时会话的一次性 Agent 执行（pipeline 阶段 / conversational 参与者使用）；
 * - 流水线文件产物的落盘。
 */
public interface ExecutionUnit {

    /**
     * 获取或创建主会话（与旧 ChatCmdExe 语义一致）。
     */
    Session getOrCreateSession(String sessionId, Agent agent);

    /**
     * 持久化主会话。
     */
    void saveSession(Session session);

    /**
     * 在会话上执行 ReAct（带进度回调 + 可选 LLM 流式回调）。
     */
    ReActResult runSession(Session session, Agent agent,
                           ProgressCallback callback, LlmStreamCallback streamCallback);

    /**
     * 用一段提示词驱动单个 Agent 执行一次 ReAct（临时会话，不入库），返回最终回复。
     * {@code streamCallback} 非空时按流式执行（token 实时回调），供 pipeline 阶段 / conversational 串行轮使用。
     */
    String runAgent(String prompt, Agent agent, ProgressCallback callback, LlmStreamCallback streamCallback);

    /**
     * 将阶段产物落盘到工作目录，返回文件路径。
     */
    Path writeArtifact(String workdir, String stageId, String content);
}
