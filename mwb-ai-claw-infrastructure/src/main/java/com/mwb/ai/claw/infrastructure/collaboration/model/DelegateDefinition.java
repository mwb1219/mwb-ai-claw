package com.mwb.ai.claw.infrastructure.collaboration;

import lombok.Data;

/**
 * 委托编排定义（orchestrations.json 中 delegate.config 的类型化对象）。
 * <p>
 * 由 TodoDelegateOrchestrator 从编排定义的宽松 config Map 解析为类型化对象；
 * 编排注册中心与定义模型不感知具体编排结构，各插件自行解释自己的 config。
 */
@Data
public class DelegateDefinition {

    /** 根节点规划 Agent id（默认 "architect"；子节点规划者 = 上一层 todo.agentId） */
    private String plannerAgentId;

    /** 单层 Todo 数量上限（默认 8；超出截断并告警） */
    private Integer maxTodos;

    /** 递归委托深度（默认 2；1=仅主 Agent 拆解一层，子 Agent 直执行；2=允许子 Agent 再拆解一层） */
    private Integer maxDepth;

    /** 无依赖 Todo 是否并行执行（默认 true） */
    private Boolean parallel;

    /** 并行度（默认 4） */
    private Integer concurrency;

    /** Todo 失败策略：abort（终止整个编排，默认）| skip（标记失败继续，汇总时注明） */
    private String onFailure;

    /** Todo 失败重试次数（默认 1；空回复同样触发重试） */
    private Integer retries;

    /** 规划 / 汇总阶段思考模式开关（null=不覆盖 Agent 默认配置） */
    private Boolean thinking;

    /** 汇总结果传递：text（直接拼入汇总 prompt，默认）| file（落盘传路径） */
    private String resultPass;

    /** 产物落盘目录（resultPass=file 时使用，默认 orchestration-artifacts） */
    private String workdir;

    /** 人工审批门禁：none（默认，不暂停）| root（仅根规划完成暂停）| all（每层规划完成暂停） */
    private String approvalGate;

    /** 审批等待超时（毫秒，默认 0=无限等待；超时后该层降级直执行） */
    private Long approvalTimeoutMs;

    /** 汇总阶段子结果相关性 top-k 注入数量（默认 3；子结果数 ≤ topK 时全量注入） */
    private Integer topK;

    /** 动态规划（Plan-Do-Reflect）re-plan 轮次（默认 0=不启用；>0 时每执行完一个 Wave 可结合已得结果调整剩余 Todo） */
    private Integer replanRounds;

    /** 根节点规划 Agent id（未配置默认 architect） */
    public String plannerAgentIdOrDefault() {
        return plannerAgentId == null || plannerAgentId.trim().isEmpty() ? "architect" : plannerAgentId.trim();
    }

    /** 单层 Todo 数量上限（未配置默认 8） */
    public int maxTodosOrDefault() {
        return maxTodos == null ? 8 : maxTodos;
    }

    /** 递归委托深度（未配置默认 2） */
    public int maxDepthOrDefault() {
        return maxDepth == null ? 2 : maxDepth;
    }

    /** 无依赖 Todo 是否并行执行（未配置默认 true） */
    public boolean parallelOrDefault() {
        return parallel == null || parallel;
    }

    /** 并行度（未配置默认 4） */
    public int concurrencyOrDefault() {
        return concurrency == null ? 4 : concurrency;
    }

    /** Todo 失败策略（未配置默认 abort） */
    public String onFailureOrDefault() {
        return onFailure == null || onFailure.trim().isEmpty() ? "abort" : onFailure.trim();
    }

    /** Todo 失败重试次数（未配置默认 1） */
    public int retriesOrDefault() {
        return retries == null ? 1 : retries;
    }

    /** 汇总结果传递方式（未配置默认 text） */
    public String resultPassOrDefault() {
        return resultPass == null || resultPass.trim().isEmpty() ? "text" : resultPass.trim();
    }

    /** 产物落盘目录（未配置默认 orchestration-artifacts） */
    public String workdirOrDefault() {
        return workdir == null || workdir.trim().isEmpty() ? "orchestration-artifacts" : workdir.trim();
    }

    /** 人工审批门禁（未配置默认 none） */
    public String approvalGateOrDefault() {
        return approvalGate == null || approvalGate.trim().isEmpty() ? "none" : approvalGate.trim();
    }

    /** 审批等待超时毫秒数（未配置默认 0=无限等待） */
    public long approvalTimeoutMsOrDefault() {
        return approvalTimeoutMs == null || approvalTimeoutMs <= 0 ? 0 : approvalTimeoutMs;
    }

    /** 汇总子结果 top-k 注入数量（未配置默认 3） */
    public int topKOrDefault() {
        return topK == null || topK < 1 ? 3 : topK;
    }

    /** 动态规划 re-plan 轮次（未配置默认 0=不启用；负值按 0 处理） */
    public int replanRoundsOrDefault() {
        return replanRounds == null || replanRounds < 0 ? 0 : replanRounds;
    }
}
