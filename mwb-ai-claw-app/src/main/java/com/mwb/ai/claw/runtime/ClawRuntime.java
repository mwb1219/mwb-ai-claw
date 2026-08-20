package com.mwb.ai.claw.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import com.mwb.ai.claw.api.AgentServiceI;
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

    /** 创建运行时构造器 */
    public static Builder builder() {
        return new Builder();
    }

    /** 发送消息（自动创建新会话） */
    public SingleResponse<ChatResponseDTO> chat(String message) {
        ChatCmd cmd = new ChatCmd();
        cmd.setMessage(message);
        return agentService.chat(cmd);
    }

    /** 发送消息（指定会话，会话不存在则创建） */
    public SingleResponse<ChatResponseDTO> chat(String sessionId, String message) {
        ChatCmd cmd = new ChatCmd();
        cmd.setSessionId(sessionId);
        cmd.setMessage(message);
        return agentService.chat(cmd);
    }

    /** 发送消息（完整命令） */
    public SingleResponse<ChatResponseDTO> chat(ChatCmd cmd) {
        return agentService.chat(cmd);
    }

    /** 创建会话 */
    public SingleResponse<SessionDTO> createSession(CreateSessionCmd cmd) {
        return agentService.createSession(cmd);
    }

    /** 查询会话 */
    public SingleResponse<SessionDTO> getSession(String sessionId) {
        return agentService.getSession(sessionId);
    }

    /** 会话列表 */
    public SingleResponse<List<SessionDTO>> listSessions() {
        return agentService.listSessions();
    }

    /** 删除会话 */
    public SingleResponse<Void> deleteSession(String sessionId) {
        return agentService.deleteSession(sessionId);
    }

    /** 暴露底层应用服务，便于高级用法 */
    public AgentServiceI agentService() {
        return agentService;
    }

    /** 关闭运行时（释放底层 Spring 上下文） */
    @Override
    public void close() {
        context.close();
    }

    /**
     * ClawRuntime 构造器。
     */
    public static class Builder {

        private final Map<String, Object> properties = new HashMap<>();
        private final List<Class<?>> userComponents = new ArrayList<>();

        /** 注入任意 Spring 属性（{@code agent.*} 等） */
        public Builder config(String key, Object value) {
            properties.put(key, value);
            return this;
        }

        /** 便捷方法：LLM API Key（agent.api-key） */
        public Builder apiKey(String apiKey) {
            return config("agent.api-key", apiKey);
        }

        /** 便捷方法：LLM 模型（agent.model） */
        public Builder model(String model) {
            return config("agent.model", model);
        }

        /** 便捷方法：LLM OpenAI 兼容 Base URL（agent.base-url） */
        public Builder baseUrl(String baseUrl) {
            return config("agent.base-url", baseUrl);
        }

        /**
         * 注册用户自定义组件（须为 Spring 组件类，如 {@code @Component} / {@code @Configuration}），
         * 用于覆盖框架默认实现（如自定义 MemoryPageStore / LlmGateway）。
         */
        public Builder register(Class<?> componentClass) {
            userComponents.add(componentClass);
            return this;
        }

        /** 构建并启动运行时 */
        public ClawRuntime build() {
            AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
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
