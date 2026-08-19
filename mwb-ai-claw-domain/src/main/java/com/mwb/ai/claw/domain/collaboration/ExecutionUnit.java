package com.mwb.ai.claw.domain.collaboration;

import java.nio.file.Path;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.core.ReActResult;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;

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

    /**
     * 将文本以精确文件名写入指定目录（目录不存在自动创建，同名文件覆盖），返回文件路径。
     * 供编排器按规范文件名落盘（如 delegate 编排的 plan.json / result.txt）。
     */
    Path writeFile(String dir, String fileName, String content);

    /**
     * 嵌套调起一个编排（按编排 id 从注册中心解析定义并执行），返回其协作结果。
     * 供编排器在 Todo 上嵌套组合其他编排（delegate 的 todo 可引用 pipeline / conversational / delegate 自身），
     * 返回结果 reply 作为该 Todo 的产出参与上层汇总。防环由各编排插件自身保证（如 delegate 的嵌套调用链检测）。
     */
    CollaborationResult runOrchestration(String message, String orchestrationId);
}
