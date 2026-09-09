package com.mwb.ai.claw.eval.dataset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.eval.model.EvalCase;
import com.mwb.ai.claw.eval.model.EvalDataset;
import com.mwb.ai.claw.eval.model.EvalRule;
import com.mwb.ai.claw.eval.model.JudgeReplyMode;
import com.mwb.ai.claw.eval.model.JudgeType;
import com.mwb.ai.claw.eval.model.RuleType;

/**
 * LLM 驱动的评测数据集生成器：按主题调用 Agent 模型，产出符合数据集规范（task + cases[prompt/expected/rule]）
 * 的 JSON，校验后落盘到指定目录（默认 {@code ./generated-datasets}），返回路径 + 摘要。
 * <p>
 * 属于 {@code mwb-ai-claw-eval} 库能力，默认以「构造注入 + 显式目录」的 POJO 形态供任意接入方复用
 * （example-web 的 {@code POST /eval/dataset/generate}、shell CLI 等），不绑定 Spring 注解。
 * 生成用例自带 {@code rule(type=contains)}，可直接用 judge=rule 做低成本确定性回归；也支持 judge=both 语义兜底。
 * 仅复用 {@link LlmGateway} / {@link AgentGateway} 已暴露的 Bean，不依赖核心执行链路。
 */
public class EvalDatasetGenerator {

    private static final int MAX_CASES = 20;
    private static final String DEFAULT_AGENT = "default";
    private static final String DEFAULT_DATASET_DIR = "./generated-datasets";

    private final LlmGateway llmGateway;
    private final AgentGateway agentGateway;
    private final DatasetLoader datasetLoader;
    private final ObjectMapper mapper;
    private final String datasetDir;

    public EvalDatasetGenerator(LlmGateway llmGateway, AgentGateway agentGateway) {
        this(llmGateway, agentGateway, DEFAULT_DATASET_DIR);
    }

    public EvalDatasetGenerator(LlmGateway llmGateway, AgentGateway agentGateway, String datasetDir) {
        this.llmGateway = llmGateway;
        this.agentGateway = agentGateway;
        this.datasetLoader = new DatasetLoader();
        this.datasetDir = datasetDir == null || datasetDir.trim().isEmpty()
                ? DEFAULT_DATASET_DIR : datasetDir.trim();
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);
    }

    /**
     * 生成并落盘一个数据集。
     *
     * @param cmd 生成命令（topic 必填，count 默认 5 上限 20）
     * @return 已落盘文件路径 + 数据集摘要 + 完整内容
     */
    public GenerateDatasetResult generate(GenerateDatasetCommand cmd) {
        if (cmd == null) {
            throw new IllegalArgumentException("生成数据集需要请求体");
        }
        String topic = cmd.getTopic();
        if (topic == null || topic.trim().isEmpty()) {
            throw new IllegalArgumentException("生成数据集需提供主题（topic）");
        }
        topic = topic.trim();
        String type = cmd.getType() == null || cmd.getType().trim().isEmpty() ? "qa" : cmd.getType().trim();
        int count = Math.max(1, Math.min(MAX_CASES, cmd.getCount() <= 0 ? 5 : cmd.getCount()));
        String agentId = cmd.getAgentId() == null || cmd.getAgentId().trim().isEmpty()
                ? DEFAULT_AGENT : cmd.getAgentId().trim();
        Agent agent = agentGateway.getAgent(agentId);
        ModelConfig modelConfig = resolveModel(agent, cmd.getModel());
        if (modelConfig == null || modelConfig.getModel() == null || modelConfig.getModel().trim().isEmpty()) {
            throw new IllegalArgumentException("生成数据集需要模型配置：请先为主 Agent 绑定模型，或用 model 指定生成模型");
        }

        String json = callLlm(topic, type, count, modelConfig);
        EvalDataset dataset = parseDataset(json, cmd, topic);
        dataset.getTask().setId(resolveTaskId(cmd, topic));
        if (dataset.getTask().getName() == null || dataset.getTask().getName().trim().isEmpty()) {
            dataset.getTask().setName(cmd.getName() != null && !cmd.getName().trim().isEmpty()
                    ? cmd.getName().trim() : topic);
        }
        dataset.getTask().setVersion("1.0");
        dataset.getTask().setEnabled(true);

        Path dir = Paths.get(datasetDir);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(dataset.getTask().getId() + ".json");
            mapper.writeValue(file.toFile(), dataset);
            // 用公共 Loader 做最终校验（task.id/用例 id 唯一/prompt/expected 必填），确保可直接被 /eval 加载
            datasetLoader.load(file.toAbsolutePath().toString());
            return buildResult(file, dataset, modelConfig.getModel());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("生成数据集失败: " + e.getMessage(), e);
        }
    }

    private GenerateDatasetResult buildResult(Path file, EvalDataset dataset, String model) {
        GenerateDatasetResult res = new GenerateDatasetResult();
        res.setDatasetPath(file.toAbsolutePath().toString());
        res.setTaskId(dataset.getTask().getId());
        res.setTaskName(dataset.getTask().getName());
        res.setCaseCount(dataset.getCases() == null ? 0 : dataset.getCases().size());
        res.setDataset(dataset);
        res.setModel(model);
        return res;
    }

    private ModelConfig resolveModel(Agent agent, String override) {
        ModelConfig base = agent == null ? null : agent.getModelConfig();
        if (override == null || override.trim().isEmpty()) {
            return base;
        }
        ModelConfig mc = base == null ? new ModelConfig() : copyModelConfig(base);
        mc.setModel(override.trim());
        mc.setTemperature(0.3);
        return mc;
    }

    private ModelConfig copyModelConfig(ModelConfig src) {
        ModelConfig mc = new ModelConfig();
        mc.setModel(src.getModel());
        mc.setProvider(src.getProvider());
        mc.setBaseUrl(src.getBaseUrl());
        mc.setApiKey(src.getApiKey());
        mc.setTemperature(0.3);
        mc.setMaxTokens(src.getMaxTokens());
        mc.setThinking(src.getThinking());
        return mc;
    }

    private String callLlm(String topic, String type, int count, ModelConfig cfg) {
        String prompt = String.format(
                "你是数据集生成助手。请围绕主题「%s」生成 %d 条用于评测 AI Agent 的用例，风格类型：%s。\n"
                        + "严格输出如下 JSON 结构（不要输出 Markdown 代码围栏、不要多余文字）：\n"
                        + "{\n"
                        + "  \"task\": { \"id\": \"<英文短横线 id，唯一>\", \"name\": \"<显示名>\", \"version\": \"1.0\", \"enabled\": true, \"defaultJudge\": \"rule\", \"mode\": \"boolean\" },\n"
                        + "  \"cases\": [\n"
                        + "    { \"id\": \"c1\", \"name\": \"<用例名>\", \"prompt\": \"<发给 Agent 的自包含输入>\", \"expected\": \"<期望答案要点>\", \"rule\": { \"type\": \"contains\", \"value\": \"<期望答案中必须出现的简短关键词>\" } }\n"
                        + "  ]\n"
                        + "}\n"
                        + "要求：\n"
                        + "- 每个 case 的 id 唯一（c1、c2、…）；prompt 为自包含的提问/任务；expected 为标准答案要点。\n"
                        + "- 每个 case 必须包含 rule（type=contains），value 必须是 Agent 正确回答中会出现的简短、无歧义关键词。\n"
                        + "- 共生成 %d 条，难度与考察角度尽量多样，避免重复。",
                topic, count, type, count);

        LlmRequest req = new LlmRequest();
        req.setModel(cfg.getModel());
        req.setMessages(Arrays.asList(
                LlmMessage.system("你是严格的数据集生成助手，永远只输出合法 JSON。"),
                LlmMessage.user(prompt)));
        req.setResponseFormat("json_object");
        req.setTemperature(0.3);
        req.setMaxTokens(cfg.getMaxTokens() > 0 ? cfg.getMaxTokens() : (800 + count * 200));

        LlmResponse resp = llmGateway.chat(req, cfg);
        String content = resp.getContent();
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("模型未返回内容，无法生成数据集");
        }
        return content;
    }

    private EvalDataset parseDataset(String content, GenerateDatasetCommand cmd, String topic) {
        String cleaned = stripFence(content);
        try {
            EvalDataset ds = mapper.readValue(cleaned, EvalDataset.class);
            if (ds == null || ds.getTask() == null) {
                throw new IllegalArgumentException("生成结果缺少 task");
            }
            if (ds.getCases() == null || ds.getCases().isEmpty()) {
                throw new IllegalArgumentException("生成结果缺少用例（cases）");
            }
            ds.getTask().setDefaultJudge(ds.getTask().getDefaultJudge() == null
                    ? JudgeType.RULE : ds.getTask().getDefaultJudge());
            ds.getTask().setMode(ds.getTask().getMode() == null
                    ? JudgeReplyMode.BOOLEAN : ds.getTask().getMode());
            for (EvalCase c : ds.getCases()) {
                c.setJudge(c.getJudge() == null ? JudgeType.RULE : c.getJudge());
                if (c.getRule() == null) {
                    EvalRule rule = new EvalRule();
                    rule.setType(RuleType.CONTAINS);
                    rule.setValue(pickKeyword(c.getExpected()));
                    c.setRule(rule);
                }
            }
            return ds;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("生成数据集解析失败: " + e.getMessage(), e);
        }
    }

    /** 兜底：模型未给 rule 时，从 expected 中截取一个短关键词作为 contains 判据。 */
    private String pickKeyword(String expected) {
        if (expected == null || expected.trim().isEmpty()) {
            return "";
        }
        String s = expected.trim().replaceAll("\\s+", " ");
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    private String stripFence(String content) {
        String cleaned = content == null ? "" : content.trim();
        if (cleaned.startsWith("```")) {
            int firstNl = cleaned.indexOf('\n');
            int lastTick = cleaned.lastIndexOf("```");
            if (firstNl > 0 && lastTick > firstNl) {
                cleaned = cleaned.substring(firstNl + 1, lastTick).trim();
            }
        }
        return cleaned;
    }

    private String resolveTaskId(GenerateDatasetCommand cmd, String topic) {
        if (cmd.getTaskId() != null && !cmd.getTaskId().trim().isEmpty()) {
            return slug(cmd.getTaskId().trim());
        }
        String base = slug(topic);
        if (base == null || base.isEmpty()) {
            base = "dataset";
        }
        return base + "-" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
    }

    private String slug(String s) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (char ch : s.toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(ch)) {
                cur.append(ch);
            } else if (cur.length() > 0) {
                if (parts.size() < 8) {
                    parts.add(cur.toString());
                }
                cur.setLength(0);
            }
        }
        if (cur.length() > 0 && parts.size() < 8) {
            parts.add(cur.toString());
        }
        return String.join("-", parts);
    }
}
