package com.mwb.ai.claw.eval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * Agent 评测（Evaluation）配置：前缀 {@code agent.eval.*}。
 * <p>
 * 评测引擎独立于核心运行时（独立模块 {@code mwb-ai-claw-eval}），因此配置亦独立于此。
 */
@Data
@ConfigurationProperties(prefix = "agent.eval")
public class EvalProperties {

    /** 评测引擎总开关 */
    private boolean enabled = true;

    /** 默认数据集文件（JSON/YAML）路径；命令未指定时使用 */
    private String datasetPath;

    /** 跑评测的主导 Agent id */
    private String agentId = "default";

    /** 判定策略：rule | llm | both（both = rule 先过、llm 兜底） */
    private String judge = "both";

    /** LLM 裁判模型（缺省继承全局模型 / .env 兜底） */
    private String judgeModel;

    /** 报告输出目录 */
    private String output = "./eval-report";

    /** 并行执行 case 数（1=串行，避免打爆本地令牌/配额） */
    private int concurrency = 1;
}