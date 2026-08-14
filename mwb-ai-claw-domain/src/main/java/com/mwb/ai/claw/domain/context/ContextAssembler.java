package com.mwb.ai.claw.domain.context;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.llm.LlmRequest;

/**
 * 上下文组装器：将各种上下文来源（system prompt、历史消息、工具定义、长期记忆等）
 * 组装为一次 LLM 请求的完整上下文。
 * <p>
 * 这是 Context Engineering 的核心入口。后续可在此扩展上下文压缩、token 预算管理、
 * 历史裁剪/摘要、检索增强注入等策略。
 */
public interface ContextAssembler {

    /**
     * 组装上下文。
     *
     * @param session 会话聚合根（含历史消息）
     * @param agent   Agent 配置
     * @return 组装后的 LLM 请求上下文
     */
    LlmRequest assemble(Session session, Agent agent);
}
