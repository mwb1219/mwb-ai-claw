package com.mwb.ai.claw.dto.data;

/**
 * Agent 业务错误码
 */
public enum AgentErrorCode {

    B_AGENT_LLM_ERROR("B_AGENT_LLM_ERROR", "LLM 调用异常"),
    B_AGENT_TOOL_NOT_FOUND("B_AGENT_TOOL_NOT_FOUND", "工具不存在"),
    B_AGENT_TOOL_EXEC_ERROR("B_AGENT_TOOL_EXEC_ERROR", "工具执行异常"),
    B_AGENT_MAX_STEPS("B_AGENT_MAX_STEPS", "达到最大推理步数限制"),
    B_AGENT_SESSION_NOT_FOUND("B_AGENT_SESSION_NOT_FOUND", "会话不存在"),
    B_AGENT_CONFIG_ERROR("B_AGENT_CONFIG_ERROR", "Agent 配置缺失");

    private final String errCode;
    private final String errDesc;

    AgentErrorCode(String errCode, String errDesc) {
        this.errCode = errCode;
        this.errDesc = errDesc;
    }

    public String getErrCode() {
        return errCode;
    }

    public String getErrDesc() {
        return errDesc;
    }
}
