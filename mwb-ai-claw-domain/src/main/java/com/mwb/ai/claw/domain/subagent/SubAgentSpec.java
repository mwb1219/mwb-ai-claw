package com.mwb.ai.claw.domain.subagent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 子代理规格（H3 · Agent-as-Tool）：描述「要生成一个怎样的子代理」。
 * <p>
 * 由主 Agent 在 ReAct 中通过 {@code spawn_agent} 工具传入，工厂（{@link SubAgentFactory}）
 * 据此动态构建一个可执行、携带独立模型 / 指令 / 预算的临时 {@code Agent} 实例。
 * 由 LLM 提供的是 {@link #task}（必填），其余为可选覆盖项。
 */
@Data
public class SubAgentSpec {

    /** 交给子代理的任务描述（必填，LLM 提供） */
    private String task;

    /** 子代理展示名（可选，默认 "sub-agent"） */
    private String name;

    /** 追加系统指令 / 人设（可选，拼到 systemPrompt 尾部） */
    private String instructions;

    /** 模型覆盖（可选，缺省继承默认 / 调用方 Agent 的模型） */
    private String model;

    /** Provider 覆盖（可选） */
    private String provider;

    /** 推理步数上限（<=0 用默认，继承基座） */
    private int maxSteps;

    /** 单次最大 tokens（<=0 用默认，继承基座） */
    private int maxTokens;

    /** 允许的工具名列表；null / 空 = 绑定全部已注册工具（缺省语义）；非空 = 强制仅绑定声明工具 */
    private List<String> tools = new ArrayList<>();

    /** 是否允许读写分层记忆（默认 true） */
    private boolean memory = true;
}
