package com.mwb.ai.claw.domain.collaboration;

import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.scope.AgentScope;
import lombok.Data;

/**
 * 编排执行上下文：编排器所需的全部输入。
 * <p>
 * domain 层不依赖 client / app 层，故以基本类型承载用户请求（而非 ChatCmd DTO）。
 */
@Data
public class OrchestrationContext {

    /** 请求级租户/用户维度；嵌套编排由上层 ctx 透传（编排器与嵌套编排取 scope 的唯一入口） */
    private AgentScope scope;

    /** 用户消息 */
    private String message;

    /** 会话 id（可空，空则新建主会话） */
    private String sessionId;

    /** 显式指定的 Agent id（可空，routing 编排内优先使用） */
    private String explicitAgentId;

    /** 显式指定的编排 id（可空，由 ChatCmdExe 解析后填入实际选中的编排） */
    private String explicitOrchestrationId;

    /** 当前编排定义 */
    private OrchestrationDefinition definition;

    /** Agent 注册表（编排器取 Agent 的唯一入口） */
    private AgentGateway agentGateway;

    /** 公共执行单元（ReActLoopService + 会话 + 产物落盘原语） */
    private ExecutionUnit executionUnit;

    /** 进度回调（可空） */
    private ProgressCallback callback;

    /** LLM 流式回调（routing 全链路支持；conversational 串行轮与收敛支持；
     *  conversational 并行首轮传 null，避免多线程交错输出终端） */
    private LlmStreamCallback streamCallback;
}
