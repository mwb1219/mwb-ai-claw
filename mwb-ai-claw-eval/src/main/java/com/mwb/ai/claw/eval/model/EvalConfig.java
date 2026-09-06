package com.mwb.ai.claw.eval.model;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 评测运行配置（非持久化，命令触发时构造）。
 * <p>
 * 缺省值取自 {@code agent.eval.*}（见 {@code EvalProperties}），命令参数可覆盖。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EvalConfig {

    /** 默认数据集文件（JSON/YAML）绝对路径或 classpath 资源 */
    private String datasetPath;

    /** 主导 Agent id（跑评测的 Agent） */
    private String agentId;

    /** 判定策略（rule | llm | both；both = rule 先过、llm 兜底） */
    private String judge;

    /** LLM 裁判模型（缺省继承全局 judge-model / .env） */
    private String judgeModel;

    /** 报告输出目录（相对/绝对路径） */
    private String output;

    /** 并行执行 case 数（1=串行，默认，避免打爆本地令牌） */
    private int concurrency = 1;

    /** 按标签过滤评测子集：{tag,value}，空不过滤 */
    private Map<String, String> filters = new HashMap<>();

    /** 已发布模型单价（元/千 token），用于成本估算；缺省仅统计 token 不计价 */
    private Double pricePerKToken;

    /** 是否落 golden baseline trace：true 时以确定性 traceId 覆写该 case 基线（本次即基线，不对比）；false 时对比既有基线 */
    private boolean recordTraces = false;
}