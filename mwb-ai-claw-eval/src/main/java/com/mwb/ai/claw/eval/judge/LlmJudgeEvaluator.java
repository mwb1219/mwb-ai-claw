package com.mwb.ai.claw.eval.judge;

import java.util.Arrays;
import java.util.Map;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mwb.ai.claw.domain.core.ModelConfig;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.llm.LlmMessage;
import com.mwb.ai.claw.domain.llm.LlmRequest;
import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.eval.model.EvalCase;
import com.mwb.ai.claw.eval.model.JudgeReplyMode;
import com.mwb.ai.claw.eval.model.JudgeType;

/**
 * LLM 语义裁判（type=llm）：对开放题 / 无可确定规则的用例做语义判分。
 * <p>
 * 构造「期望 golden answer + Agent 输出」的裁判 prompt，调 {@link LlmGateway.chat}
 * 并强制 JSON 输出，解析 {@code {passed, score, verdict}}。得分口径由 {@link JudgeReplyMode} 引导
 * （boolean→0/1、number→0-10、quote→0/1 依据是否命中期望要点）。
 */
public class LlmJudgeEvaluator implements Evaluator {

    private final LlmGateway llmGateway;
    private final ModelConfig judgeConfig;
    private final ObjectMapper mapper;

    public LlmJudgeEvaluator(LlmGateway llmGateway, ModelConfig judgeConfig) {
        this.llmGateway = llmGateway;
        this.judgeConfig = judgeConfig;
        this.mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public JudgeType type() {
        return JudgeType.LLM;
    }

    @Override
    public JudgeVerdict judge(EvalCase c, String reply) {
        if (c == null || c.getPrompt() == null) {
            return null;
        }
        if (c.getMode() == null) {
            c.setMode(JudgeReplyMode.BOOLEAN);
        }
        try {
            String content = callJudge(c, reply);
            Map<String, Object> json = parse(content);
            boolean passed = toBool(json.get("passed"));
            Double score = toScore(json.get("score"));
            String verdict = json.get("verdict") == null ? content : String.valueOf(json.get("verdict"));
            return new JudgeVerdict(passed, score, verdict, JudgeType.LLM);
        } catch (Exception e) {
            return JudgeVerdict.fail(0D, "LLM 裁判异常: " + e.getMessage(), JudgeType.LLM);
        }
    }

    private String callJudge(EvalCase c, String reply) {
        String prompt = String.format(
                "你是严格的 Agent 评测裁判。依据「期望答案」判断 Agent 的回答是否达标。\n"
                        + "期望答案:\n%s\n\n"
                        + "Agent 回答:\n%s\n\n"
                        + "请只输出一个 JSON 对象，不要多余文字："
                        + "{\"passed\": true或false, \"score\": %s, \"verdict\": \"一句话理由\"}\n"
                        + "其中 score：%s。",
                c.getExpected() == null ? "" : c.getExpected(),
                reply == null ? "" : reply,
                scoreExample(c.getMode()),
                scoreRule(c.getMode()));

        LlmRequest req = new LlmRequest();
        req.setModel(judgeConfig.getModel());
        req.setMessages(Arrays.asList(
                LlmMessage.system("你是一个严格的公平评测裁判，永远输出合法 JSON。"),
                LlmMessage.user(prompt)));
        req.setResponseFormat("json_object");
        req.setTemperature(0.0);
        req.setMaxTokens(judgeConfig.getMaxTokens() > 0 ? judgeConfig.getMaxTokens() : 512);

        LlmResponse resp = llmGateway.chat(req, judgeConfig);
        return resp.getContent();
    }

    private Map<String, Object> parse(String content) throws Exception {
        String cleaned = content == null ? "" : content.trim();
        // 防 LLM 包裹 ```json 围栏
        if (cleaned.startsWith("```")) {
            int firstNl = cleaned.indexOf('\n');
            int lastTick = cleaned.lastIndexOf("```");
            if (firstNl > 0 && lastTick > firstNl) {
                cleaned = cleaned.substring(firstNl + 1, lastTick).trim();
            }
        }
        return mapper.readValue(cleaned, Map.class);
    }

    private boolean toBool(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    private Double toScore(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String scoreExample(JudgeReplyMode mode) {
        switch (mode) {
            case NUMBER:
                return "0 到 10 之间的数字";
            default:
                return "0 或 1";
        }
    }

    private String scoreRule(JudgeReplyMode mode) {
        switch (mode) {
            case NUMBER:
                return "0-10 整数分，10 为完全符合期望";
            case QUOTE:
                return "0 或 1：1 表示回答明确包含/体现了期望答案的要点";
            default:
                return "0 或 1：1 表示回答达标，0 表示不达标";
        }
    }
}