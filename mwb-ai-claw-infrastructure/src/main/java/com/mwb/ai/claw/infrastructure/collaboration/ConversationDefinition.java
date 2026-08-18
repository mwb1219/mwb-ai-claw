package com.mwb.ai.claw.infrastructure.collaboration;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话式编排定义（orchestrations.json 中 conversational.config 的类型化对象）。
 * <p>
 * 由 ConversationalOrchestrator 从编排定义的宽松 config Map 解析为类型化对象；
 * 编排注册中心与定义模型不感知具体编排结构，各插件自行解释自己的 config。
 */
@Data
public class ConversationDefinition {

    /** 讨论最大轮数（1 为首轮观点，之后为讨论轮；建议 1-4） */
    private Integer rounds;

    /** 收敛 Agent id（convergence=moderator 时必填，引用 agents.json 注册表） */
    private String moderator;

    /** 参与讨论的 Agent id 列表（至少 2 个） */
    private List<String> participants = new ArrayList<>();

    /** 共识阈值：某观点被 >= 该数量的参与者支持即提前收敛（默认 2，仅 consensus 生效） */
    private Integer minConsensus;

    /** 收敛策略：consensus（观点多数一致）| moderator（默认，仲裁汇总）| best（置信度最高） */
    private String convergence;

    /** 讨论轮可见历史轮数（默认 1，控制上下文占用） */
    private Integer visibleHistory;

    /** 思考模式开关（null=不覆盖 Agent 默认配置） */
    private Boolean thinking;

    /** 讨论最大轮数（未配置默认 2） */
    public int roundsOrDefault() {
        return rounds == null ? 2 : rounds;
    }

    /** 共识阈值（未配置默认 2） */
    public int minConsensusOrDefault() {
        return minConsensus == null ? 2 : minConsensus;
    }

    /** 讨论轮可见历史轮数（未配置默认 1） */
    public int visibleHistoryOrDefault() {
        return visibleHistory == null ? 1 : visibleHistory;
    }

    /** 收敛策略（未配置默认 moderator） */
    public String convergenceOrDefault() {
        return convergence == null || convergence.trim().isEmpty() ? "moderator" : convergence.trim();
    }
}
