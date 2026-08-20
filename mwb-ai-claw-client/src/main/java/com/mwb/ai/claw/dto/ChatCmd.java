package com.mwb.ai.claw.dto;

import java.util.List;
import java.util.Map;

import com.mwb.ai.claw.domain.llm.ContentPart;

import lombok.Data;

/**
 * Agent 对话命令
 */
@Data
public class ChatCmd {

    /** 会话 ID，为空则自动创建新会话 */
    private String sessionId;

    /** Agent 标识，为空则使用默认 Agent */
    private String agentId;

    /** 编排 id（显式指定协作编排；为空则使用默认编排 agent.orchestration，多 Agent 协作经 invoke_* 工具由主 Agent 自主发起） */
    private String orchestrationId;

    /** 用户输入消息 */
    private String message;

    /** 结构化输出（D2）：text / json_object / json_schema；为空则按默认自由文本输出 */
    private String responseFormat;

    /** json_schema 严格 schema（responseFormat=json_schema 时生效） */
    private Map<String, Object> jsonSchema;

    /** 多模态内容片段（D2）：非空时优先于 message 作为用户消息内容（image_url / image_base64） */
    private List<ContentPart> parts;
}
