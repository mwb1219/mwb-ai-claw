package com.mwb.ai.claw.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import com.mwb.ai.claw.agent.executor.ChatCmdExe;
import com.mwb.ai.claw.agent.executor.CreateSessionCmdExe;
import com.mwb.ai.claw.agent.executor.SessionDeleteCmdExe;
import com.mwb.ai.claw.agent.executor.SessionListQryExe;
import com.mwb.ai.claw.agent.executor.SessionQueryExe;
import com.mwb.ai.claw.api.AgentServiceI;
import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.domain.scope.AgentScopeContext;
import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.CreateSessionCmd;
import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import com.mwb.ai.claw.dto.data.SessionDTO;

import lombok.extern.slf4j.Slf4j;

/**
 * 客户端嵌入式运行时入口（无 Web 容器）。
 * <p>
 * 供其他 JVM 应用直接调用 Agent 能力：
 * <pre>{@code
 * ClawRuntime runtime = ClawRuntime.builder()
 *         .apiKey("sk-...")
 *         .model("deepseek-chat")
 *         .baseUrl("https://api.deepseek.com/v1")
 *         .build();
 * String reply = runtime.chat("你好").getData().getReply();
 * runtime.close();
 * }</pre>
 * <p>
 * 多租户：所有接口均提供 {@link AgentScope} 重载（如 {@code chat(String, AgentScope)}），
 * 传 {@code AgentScope.of("租户id", "用户id")} 即可在指定租户/用户维度下隔离会话、记忆与缓存；
 * 不传则使用默认空间。scope 仅在调用线程内生效（执行结束自动清理）。
 * <p>
 * 实现：内部启动一个嵌入式 {@link AnnotationConfigApplicationContext}，仅扫描
 * {@code infrastructure}（记忆 / LLM / 工具 / 编排 / 文件存储）与 {@code agent}
 * （应用层用例）两个包，复用与服务端完全相同的 Bean 装配；通过
 * {@link Builder#register(Class)} 注册自定义组件可覆盖默认实现
 * （依赖默认实现上的 {@code @ConditionalOnMissingBean}）。
 * <p>
 * 配置覆盖：{@code agent.api-key} / {@code agent.model} / {@code agent.base-url} 等
 * {@code agent.*} 属性均可通过 {@link Builder#config(String, Object)} 注入。
 *
 * @author Frank Zhang
 */
@Slf4j
public class ClawRuntime implements AutoCloseable {

    private final AnnotationConfigApplicationContext context;
    private final AgentServiceI agentService;

    private ClawRuntime(AnnotationConfigApplicationContext context) {
        this.context = context;
        this.agentService = context.getBean(AgentServiceI.class);
        log.info("ClawRuntime 启动完成，已就绪");
    }

    /**
     * 创建运行时构造器。
     *
     * @return 运行时构造器 {@link Builder}，用于链式配置 LLM 参数、自定义组件后构建 {@link ClawRuntime}
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 发送消息（自动创建新会话，默认空间）。
     *
     * @param message 用户输入消息，非空
     * @return 对话结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link ChatResponseDTO}（含会话 ID、回复内容、Agent ID、编排 ID、推理步骤）
     */
    public SingleResponse<ChatResponseDTO> chat(String message) {
        ChatCmd cmd = new ChatCmd();
        cmd.setMessage(message);
        return chat(cmd, null);
    }

    /**
     * 发送消息（自动创建新会话，指定租户/用户维度）。
     *
     * @param message 用户输入消息，非空
     * @param scope   租户/用户维度；为 {@code null} 时使用默认空间
     * @return 对话结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link ChatResponseDTO}（含会话 ID、回复内容、Agent ID、编排 ID、推理步骤）
     */
    public SingleResponse<ChatResponseDTO> chat(String message, AgentScope scope) {
        ChatCmd cmd = new ChatCmd();
        cmd.setMessage(message);
        return chat(cmd, scope);
    }

    /**
     * 发送消息（指定会话，会话不存在则创建，默认空间）。
     *
     * @param sessionId 会话 ID；为空或不存在时自动创建新会话
     * @param message   用户输入消息，非空
     * @return 对话结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link ChatResponseDTO}（含会话 ID、回复内容、Agent ID、编排 ID、推理步骤）
     */
    public SingleResponse<ChatResponseDTO> chat(String sessionId, String message) {
        return chat(sessionId, message, null);
    }

    /**
     * 发送消息（指定会话，会话不存在则创建，指定租户/用户维度）。
     *
     * @param sessionId 会话 ID；为空或不存在时自动创建新会话
     * @param message   用户输入消息，非空
     * @param scope     租户/用户维度；为 {@code null} 时使用默认空间
     * @return 对话结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link ChatResponseDTO}（含会话 ID、回复内容、Agent ID、编排 ID、推理步骤）
     */
    public SingleResponse<ChatResponseDTO> chat(String sessionId, String message, AgentScope scope) {
        ChatCmd cmd = new ChatCmd();
        cmd.setSessionId(sessionId);
        cmd.setMessage(message);
        return chat(cmd, scope);
    }

    /**
     * 发送消息（完整命令，默认空间）。
     *
     * @param cmd 完整对话命令 {@link ChatCmd}（可指定会话 ID、Agent ID、编排 ID、结构化输出等），非空
     * @return 对话结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link ChatResponseDTO}（含会话 ID、回复内容、Agent ID、编排 ID、推理步骤）
     */
    public SingleResponse<ChatResponseDTO> chat(ChatCmd cmd) {
        return chat(cmd, null);
    }

    /**
     * 发送消息（完整命令，指定租户/用户维度）。
     *
     * @param cmd   完整对话命令 {@link ChatCmd}（可指定会话 ID、Agent ID、编排 ID、结构化输出等），非空
     * @param scope 租户/用户维度；为 {@code null} 时使用默认空间
     * @return 对话结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link ChatResponseDTO}（含会话 ID、回复内容、Agent ID、编排 ID、推理步骤）
     */
    public SingleResponse<ChatResponseDTO> chat(ChatCmd cmd, AgentScope scope) {
        return withScope(scope, () -> context.getBean(ChatCmdExe.class).execute(cmd));
    }

    /**
     * 流式发送消息（自动创建新会话，默认空间），增量 token 通过 callback 回调。
     *
     * @param message            用户输入消息，非空
     * @param progressCallback   执行进度回调（ReAct 各阶段状态），可为 {@code null}
     * @param llmStreamCallback  流式输出回调（增量 token / 工具调用片段 / 完成事件），可为 {@code null}
     * @return 对话结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link ChatResponseDTO}（含会话 ID、聚合回复、Agent ID、编排 ID、推理步骤）
     */
    public SingleResponse<ChatResponseDTO> chatStream(String message, ProgressCallback progressCallback, LlmStreamCallback llmStreamCallback) {
        return chatStream(message, progressCallback, llmStreamCallback, null);
    }

    /**
     * 流式发送消息（自动创建新会话，指定租户/用户维度），增量 token 通过 callback 回调。
     *
     * @param message            用户输入消息，非空
     * @param progressCallback   执行进度回调（ReAct 各阶段状态），可为 {@code null}
     * @param llmStreamCallback  流式输出回调（增量 token / 工具调用片段 / 完成事件），可为 {@code null}
     * @param scope              租户/用户维度；为 {@code null} 时使用默认空间
     * @return 对话结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link ChatResponseDTO}（含会话 ID、聚合回复、Agent ID、编排 ID、推理步骤）
     */
    public SingleResponse<ChatResponseDTO> chatStream(String message, ProgressCallback progressCallback,
                                                     LlmStreamCallback llmStreamCallback, AgentScope scope) {
        ChatCmd cmd = new ChatCmd();
        cmd.setMessage(message);
        return chatStream(cmd, progressCallback, llmStreamCallback, scope);
    }

    /**
     * 流式发送消息（指定会话，会话不存在则创建，默认空间），增量 token 通过 callback 回调。
     *
     * @param sessionId          会话 ID；为空或不存在时自动创建新会话
     * @param message            用户输入消息，非空
     * @param progressCallback   执行进度回调（ReAct 各阶段状态），可为 {@code null}
     * @param llmStreamCallback  流式输出回调（增量 token / 工具调用片段 / 完成事件），可为 {@code null}
     * @return 对话结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link ChatResponseDTO}（含会话 ID、聚合回复、Agent ID、编排 ID、推理步骤）
     */
    public SingleResponse<ChatResponseDTO> chatStream(String sessionId, String message,
                                                      ProgressCallback progressCallback, LlmStreamCallback llmStreamCallback) {
        return chatStream(sessionId, message, progressCallback, llmStreamCallback, null);
    }

    /**
     * 流式发送消息（指定会话，会话不存在则创建，指定租户/用户维度），增量 token 通过 callback 回调。
     *
     * @param sessionId          会话 ID；为空或不存在时自动创建新会话
     * @param message            用户输入消息，非空
     * @param progressCallback   执行进度回调（ReAct 各阶段状态），可为 {@code null}
     * @param llmStreamCallback  流式输出回调（增量 token / 工具调用片段 / 完成事件），可为 {@code null}
     * @param scope              租户/用户维度；为 {@code null} 时使用默认空间
     * @return 对话结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link ChatResponseDTO}（含会话 ID、聚合回复、Agent ID、编排 ID、推理步骤）
     */
    public SingleResponse<ChatResponseDTO> chatStream(String sessionId, String message,
                                                      ProgressCallback progressCallback, LlmStreamCallback llmStreamCallback,
                                                      AgentScope scope) {
        ChatCmd cmd = new ChatCmd();
        cmd.setSessionId(sessionId);
        cmd.setMessage(message);
        return chatStream(cmd, progressCallback, llmStreamCallback, scope);
    }

    /**
     * 流式发送消息（完整命令，默认空间）。
     * <p>
     * 调用线程阻塞直至本轮对话结束：增量 token / 工具调用片段经 {@link LlmStreamCallback} 实时回调
     * （{@code onToken} / {@code onToolName} / {@code onToolArguments}），结束后
     * {@code onComplete} 收到聚合响应，方法返回最终 {@link SingleResponse}。
     *
     * @param cmd                完整对话命令 {@link ChatCmd}，非空
     * @param progressCallback   执行进度回调（ReAct 各阶段状态），可为 {@code null}
     * @param llmStreamCallback  流式输出回调（增量 token / 工具调用片段 / 完成事件），可为 {@code null}
     * @return 对话结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link ChatResponseDTO}（含会话 ID、聚合回复、Agent ID、编排 ID、推理步骤）
     */
    public SingleResponse<ChatResponseDTO> chatStream(ChatCmd cmd, ProgressCallback progressCallback,
                                                      LlmStreamCallback llmStreamCallback) {
        return chatStream(cmd, progressCallback, llmStreamCallback, null);
    }

    /**
     * 流式发送消息（完整命令，指定租户/用户维度）。
     * <p>
     * 调用线程阻塞直至本轮对话结束：增量 token / 工具调用片段经 {@link LlmStreamCallback} 实时回调
     * （{@code onToken} / {@code onToolName} / {@code onToolArguments}），结束后
     * {@code onComplete} 收到聚合响应，方法返回最终 {@link SingleResponse}。
     *
     * @param cmd                完整对话命令 {@link ChatCmd}，非空
     * @param progressCallback   执行进度回调（ReAct 各阶段状态），可为 {@code null}
     * @param llmStreamCallback  流式输出回调（增量 token / 工具调用片段 / 完成事件），可为 {@code null}
     * @param scope              租户/用户维度；为 {@code null} 时使用默认空间
     * @return 对话结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link ChatResponseDTO}（含会话 ID、聚合回复、Agent ID、编排 ID、推理步骤）
     */
    public SingleResponse<ChatResponseDTO> chatStream(ChatCmd cmd, ProgressCallback progressCallback,
                                                      LlmStreamCallback llmStreamCallback, AgentScope scope) {
        return withScope(scope, () -> context.getBean(ChatCmdExe.class).execute(cmd, progressCallback, llmStreamCallback));
    }

    /**
     * 创建会话（默认空间）。
     *
     * @param cmd 创建会话命令 {@link CreateSessionCmd}（可指定 Agent ID、会话标题），非空
     * @return 创建结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link SessionDTO}（含会话 ID、Agent ID、标题等）
     */
    public SingleResponse<SessionDTO> createSession(CreateSessionCmd cmd) {
        return createSession(cmd, null);
    }

    /**
     * 创建会话（指定租户/用户维度）。
     *
     * @param cmd   创建会话命令 {@link CreateSessionCmd}（可指定 Agent ID、会话标题），非空
     * @param scope 租户/用户维度；为 {@code null} 时使用默认空间
     * @return 创建结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link SessionDTO}（含会话 ID、Agent ID、标题等）
     */
    public SingleResponse<SessionDTO> createSession(CreateSessionCmd cmd, AgentScope scope) {
        return withScope(scope, () -> context.getBean(CreateSessionCmdExe.class).execute(cmd));
    }

    /**
     * 查询会话（默认空间）。
     *
     * @param sessionId 会话 ID，非空
     * @return 查询结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link SessionDTO}（含会话 ID、Agent ID、标题等）
     */
    public SingleResponse<SessionDTO> getSession(String sessionId) {
        return getSession(sessionId, null);
    }

    /**
     * 查询会话（指定租户/用户维度）。
     *
     * @param sessionId 会话 ID，非空
     * @param scope     租户/用户维度；为 {@code null} 时使用默认空间
     * @return 查询结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回 {@link SessionDTO}（含会话 ID、Agent ID、标题等）
     */
    public SingleResponse<SessionDTO> getSession(String sessionId, AgentScope scope) {
        return withScope(scope, () -> context.getBean(SessionQueryExe.class).execute(sessionId));
    }

    /**
     * 会话列表（默认空间）。
     *
     * @return 查询结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回当前空间下的 {@link SessionDTO} 列表
     */
    public SingleResponse<List<SessionDTO>> listSessions() {
        return listSessions(null);
    }

    /**
     * 会话列表（指定租户/用户维度）。
     *
     * @param scope 租户/用户维度；为 {@code null} 时使用默认空间
     * @return 查询结果；成功时 {@code isSuccess()==true}，
     *         {@code getData()} 返回指定空间下的 {@link SessionDTO} 列表
     */
    public SingleResponse<List<SessionDTO>> listSessions(AgentScope scope) {
        return withScope(scope, () -> context.getBean(SessionListQryExe.class).execute());
    }

    /**
     * 删除会话（默认空间）。
     *
     * @param sessionId 会话 ID，非空
     * @return 删除结果；成功时 {@code isSuccess()==true}
     */
    public SingleResponse<Void> deleteSession(String sessionId) {
        return deleteSession(sessionId, null);
    }

    /**
     * 删除会话（指定租户/用户维度）。
     *
     * @param sessionId 会话 ID，非空
     * @param scope     租户/用户维度；为 {@code null} 时使用默认空间
     * @return 删除结果；成功时 {@code isSuccess()==true}
     */
    public SingleResponse<Void> deleteSession(String sessionId, AgentScope scope) {
        return withScope(scope, () -> context.getBean(SessionDeleteCmdExe.class).execute(sessionId));
    }

    /**
     * 暴露底层应用服务，便于高级用法。
     *
     * @return 应用层服务门面 {@link AgentServiceI}
     */
    public AgentServiceI agentService() {
        return agentService;
    }

    /**
     * 关闭运行时（释放底层 Spring 上下文）。
     * <p>
     * 使用完毕后必须调用，避免资源泄露；已关闭的运行时不可再调用业务方法。
     */
    @Override
    public void close() {
        context.close();
    }

    /**
     * 在指定租户/用户维度下执行动作：绑定 {@link AgentScopeContext}（应用层各执行器据此读写
     * 对应维度的会话/记忆/缓存），执行结束清理当前线程，防止 ThreadLocal 泄露。
     *
     * @param scope  租户/用户维度；为 {@code null} 时使用默认空间
     * @param action 待执行的业务动作
     * @param <T>    动作返回值类型
     * @return 动作的执行结果
     */
    private <T> T withScope(AgentScope scope, Supplier<T> action) {
        AgentScopeContext.set(scope != null ? scope : AgentScope.defaultScope());
        try {
            return action.get();
        } finally {
            AgentScopeContext.clear();
        }
    }

    /**
     * ClawRuntime 构造器。
     */
    public static class Builder {

        private final Map<String, Object> properties = new HashMap<>();
        private final List<Class<?>> userComponents = new ArrayList<>();

        /**
         * 注入任意 Spring 属性（{@code agent.*} 等），用于覆盖内置默认配置。
         *
         * @param key   属性键，如 {@code agent.model} / {@code agent.max-tokens}
         * @param value 属性值
         * @return 当前构造器（链式调用）
         */
        public Builder config(String key, Object value) {
            properties.put(key, value);
            return this;
        }

        /**
         * 便捷方法：配置 LLM API Key。
         *
         * @param apiKey OpenAI 兼容 API Key（对应配置项 {@code agent.api-key}）
         * @return 当前构造器（链式调用）
         */
        public Builder apiKey(String apiKey) {
            return config("agent.api-key", apiKey);
        }

        /**
         * 便捷方法：配置 LLM 模型。
         *
         * @param model 模型名称（对应配置项 {@code agent.model}），如 {@code deepseek-chat}
         * @return 当前构造器（链式调用）
         */
        public Builder model(String model) {
            return config("agent.model", model);
        }

        /**
         * 便捷方法：配置 LLM OpenAI 兼容 Base URL。
         *
         * @param baseUrl 服务地址（对应配置项 {@code agent.base-url}），如 {@code https://api.deepseek.com/v1}
         * @return 当前构造器（链式调用）
         */
        public Builder baseUrl(String baseUrl) {
            return config("agent.base-url", baseUrl);
        }

        /**
         * 注册用户自定义组件（须为 Spring 组件类，如 {@code @Component} / {@code @Configuration}），
         * 用于覆盖框架默认实现（如自定义 MemoryPageStore / LlmGateway）。
         *
         * @param componentClass 自定义组件类
         * @return 当前构造器（链式调用）
         */
        public Builder register(Class<?> componentClass) {
            userComponents.add(componentClass);
            return this;
        }

        /**
         * 构建并启动运行时（内部启动嵌入式 Spring 上下文，装配全部 Agent 能力）。
         *
         * @return 已就绪的 {@link ClawRuntime}，可立即调用业务方法；用毕请调用 {@link ClawRuntime#close()} 释放
         */
        public ClawRuntime build() {
            AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
            // 纯 Spring 上下文需手动注册 Boot 属性绑定（@ConfigurationProperties 依赖的 binder/后处理器），
            // 否则注入的 agent.* 等属性不会绑定到配置类
            ctx.register(ConfigurationPropertiesAutoConfiguration.class);
            if (!properties.isEmpty()) {
                ctx.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("clawRuntimeConfig", properties));
            }
            // 用户组件优先注册，默认实现上的 @ConditionalOnMissingBean 将跳过同名 Bean
            for (Class<?> componentClass : userComponents) {
                ctx.register(componentClass);
            }
            ctx.scan("com.mwb.ai.claw.infrastructure", "com.mwb.ai.claw.agent");
            ctx.refresh();
            return new ClawRuntime(ctx);
        }
    }
}
