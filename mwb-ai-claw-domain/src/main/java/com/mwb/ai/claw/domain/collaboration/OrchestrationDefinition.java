package com.mwb.ai.claw.domain.collaboration;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排定义：orchestrations.json 中的一条编排（Agent 配置与编排解耦）。
 * <p>
 * - {@code id}：编排 id（意图选择器返回 / ChatCmd 显式指定引用）；
 * - {@code type}：编排插件类型（routing | pipeline | conversational，由 AgentOrchestrator.type() 匹配）；
 * - {@code description} / {@code keywords}：意图匹配元数据（供规则 / LLM 选择器判断用户意图）；
 * - {@code config}：宽松编排参数，由对应插件自行解释（插件化核心：注册中心与定义模型不感知具体编排结构）。
 */
@Data
public class OrchestrationDefinition {

    /** 编排 id（全局唯一） */
    private String id;

    /** 编排插件类型：routing | pipeline | conversational */
    private String type;

    /** 能力描述（供 LLM 选择器语义判断） */
    private String description;

    /** 意图关键词（供规则选择器匹配） */
    private List<String> keywords = new ArrayList<>();

    /** 编排参数（插件自行解释，宽松 JSON） */
    private Map<String, Object> config = new HashMap<>();

    /** 引用的 agentId（启动校验用，可选） */
    private List<String> agents = new ArrayList<>();
}
