package com.mwb.ai.claw.domain.context;

import java.util.Map;

/**
 * 单次对话请求的 LLM 参数上下文（线程绑定，生命周期 = 一次 ChatCmd 执行）。
 * <p>
 * 承载结构化输出参数（D2）：responseFormat / jsonSchema，供 ReAct 循环内的
 * {@code ContextAssembler.assemble} 注入到每次 LLM 请求。采用 ThreadLocal 传递，
 * 避免改动 ContextAssembler / Session 等接口签名（与 RunTokenBudget 同一模式）。
 * <p>
 * 用法：入口（如 ChatCmdExe.execute）在请求非空时 bind，finally 中 unbind。
 */
public class LlmRequestOptions {

    private static final ThreadLocal<LlmRequestOptions> HOLDER = new ThreadLocal<>();

    private final String responseFormat;
    private final Map<String, Object> jsonSchema;

    private LlmRequestOptions(String responseFormat, Map<String, Object> jsonSchema) {
        this.responseFormat = responseFormat;
        this.jsonSchema = jsonSchema;
    }

    /** 绑定当前线程；已有绑定时以新值覆盖（嵌套入口以最内层为准） */
    public static void bind(String responseFormat, Map<String, Object> jsonSchema) {
        if (responseFormat == null || responseFormat.trim().isEmpty()) {
            return;
        }
        HOLDER.set(new LlmRequestOptions(responseFormat.trim(), jsonSchema));
    }

    /** 解绑当前线程；未绑定时静默无操作 */
    public static void unbind() {
        HOLDER.remove();
    }

    /** 获取当前线程绑定（未绑定返回 null） */
    public static LlmRequestOptions get() {
        return HOLDER.get();
    }

    public String getResponseFormat() {
        return responseFormat;
    }

    public Map<String, Object> getJsonSchema() {
        return jsonSchema;
    }
}
