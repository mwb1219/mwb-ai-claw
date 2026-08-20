package com.mwb.ai.claw.infrastructure.autoconfigure;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.llm.EmbeddingGateway;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.memory.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.memory.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.memory.MemoryGateway;
import com.mwb.ai.claw.domain.memory.MemoryPageStore;
import com.mwb.ai.claw.domain.memory.MemoryRetriever;
import com.mwb.ai.claw.domain.memory.MemorySynthesizer;
import com.mwb.ai.claw.domain.skill.SkillGateway;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.tool.ToolPermissionChecker;
import com.mwb.ai.claw.infrastructure.auth.ConfigToolPermissionChecker;
import com.mwb.ai.claw.infrastructure.collaboration.LocalSessionLockManager;
import com.mwb.ai.claw.infrastructure.collaboration.RedisSessionLockManager;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.config.AgentRegistryLoader;
import com.mwb.ai.claw.infrastructure.config.AuthProperties;
import com.mwb.ai.claw.infrastructure.core.AgentGatewayImpl;
import com.mwb.ai.claw.infrastructure.llm.LlmGatewayImpl;
import com.mwb.ai.claw.infrastructure.llm.OpenAiEmbeddingGateway;
import com.mwb.ai.claw.infrastructure.llm.ResilientLlmGateway;
import com.mwb.ai.claw.infrastructure.llm.provider.AnthropicLlmGateway;
import com.mwb.ai.claw.infrastructure.llm.provider.GeminiLlmGateway;
import com.mwb.ai.claw.infrastructure.llm.provider.ProviderRoutingGateway;
import com.mwb.ai.claw.infrastructure.memory.FileBasedMemoryGateway;
import com.mwb.ai.claw.infrastructure.memory.FileBasedSessionGateway;
import com.mwb.ai.claw.infrastructure.memory.FileMemoryPageStore;
import com.mwb.ai.claw.infrastructure.memory.LayeredMemoryGatewayImpl;
import com.mwb.ai.claw.infrastructure.memory.MemorySynthesisExecutor;
import com.mwb.ai.claw.infrastructure.memory.SynthesisCache;
import com.mwb.ai.claw.infrastructure.memory.strategy.LlmMemorySynthesizer;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;
import com.mwb.ai.claw.infrastructure.skill.SkillLoader;
import com.mwb.ai.claw.infrastructure.skill.SkillRegistryImpl;
import com.mwb.ai.claw.infrastructure.storage.jdbc.JdbcLongTermMemoryGateway;
import com.mwb.ai.claw.infrastructure.storage.jdbc.JdbcMemoryPageStore;
import com.mwb.ai.claw.infrastructure.storage.jdbc.JdbcSessionGateway;
import com.mwb.ai.claw.infrastructure.storage.redis.RedisLongTermMemoryGateway;
import com.mwb.ai.claw.infrastructure.storage.redis.RedisMemoryPageStore;
import com.mwb.ai.claw.infrastructure.storage.redis.RedisSessionGateway;
import com.mwb.ai.claw.infrastructure.tool.ToolGatewayImpl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 核心端口实现自动装配（框架默认实现，使用方可覆盖）。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>所有可替换的默认实现均为普通 POJO（无 {@code @Component}），统一在此以
 *       {@code @Bean} + 方法级 {@code @ConditionalOnMissingBean} 注册。条件在自动装配
 *       阶段（用户 Bean 已注册之后）评估，使用方声明同名接口的 Bean 即可覆盖默认实现；</li>
 *   <li>存储后端三选一：{@code agent.storage.type}=file（默认）| jdbc | redis，分别注册
 *       会话 / 长期记忆 / 记忆页三组实现；</li>
 *   <li>会话锁二选一：{@code agent.storage.lock-type}=local（默认）| redis；</li>
 *   <li>技能开关：{@code agent.skills-enabled}（默认 true）控制 SkillGateway 是否注册。</li>
 * </ul>
 *
 * @author Frank Zhang
 */
@AutoConfiguration
public class ClawCoreAutoConfiguration {

    // ==================== 可观测性 ====================

    @Bean
    @ConditionalOnMissingBean
    public MetricsRecorder metricsRecorder(ObjectProvider<MeterRegistry> registryProvider) {
        // 有 actuator 时使用 Spring 容器注册表，否则 SimpleMeterRegistry 内存计数
        MeterRegistry registry = registryProvider.getIfAvailable(() -> new SimpleMeterRegistry());
        return new MetricsRecorder(registry);
    }

    // ==================== 权限 ====================

    @Bean
    @ConditionalOnMissingBean(ToolPermissionChecker.class)
    public ConfigToolPermissionChecker configToolPermissionChecker(AuthProperties authProperties) {
        return new ConfigToolPermissionChecker(authProperties);
    }

    // ==================== LLM ====================

    @Bean
    @ConditionalOnMissingBean(LlmGateway.class)
    public LlmGateway llmGateway(org.springframework.web.client.RestTemplate restTemplate,
                                 MetricsRecorder metrics, AgentProperties properties) {
        // 默认实现：Provider 路由网关（openai/ollama/anthropic/gemini）外层包韧性装饰器
        //（重试/退避/fallback/token 预算）；用户自定义 LlmGateway Bean 不经包装
        int connect = properties.getLlm().getConnectTimeoutMs();
        int read = properties.getLlm().getReadTimeoutMs();
        LlmGateway openAi = new LlmGatewayImpl(restTemplate, metrics, connect, read);
        LlmGateway anthropic = new AnthropicLlmGateway(restTemplate, metrics, connect, read);
        LlmGateway gemini = new GeminiLlmGateway(restTemplate, metrics, connect, read);
        LlmGateway router = new ProviderRoutingGateway(openAi, anthropic, gemini);
        return new ResilientLlmGateway(router, properties.getLlm(), metrics);
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingGateway.class)
    public OpenAiEmbeddingGateway openAiEmbeddingGateway(AgentProperties properties,
                                                         org.springframework.web.client.RestTemplate restTemplate) {
        return new OpenAiEmbeddingGateway(properties, restTemplate);
    }

    // ==================== 记忆提炼与分层门面 ====================

    @Bean
    @ConditionalOnMissingBean(MemorySynthesizer.class)
    public LlmMemorySynthesizer llmMemorySynthesizer(LlmGateway llmGateway,
                                                     AgentProperties properties,
                                                     SynthesisCache cache) {
        return new LlmMemorySynthesizer(llmGateway, properties, cache);
    }

    @Bean
    @ConditionalOnMissingBean(LayeredMemoryGateway.class)
    public LayeredMemoryGatewayImpl layeredMemoryGateway(AgentProperties properties,
                                                         MemoryPageStore pageStore,
                                                         MemorySynthesizer synthesizer,
                                                         MemoryRetriever retriever,
                                                         MemorySynthesisExecutor synthesisExecutor) {
        return new LayeredMemoryGatewayImpl(properties, pageStore, synthesizer, retriever, synthesisExecutor);
    }

    // ==================== 技能 ====================

    @Bean
    @ConditionalOnMissingBean(SkillGateway.class)
    @ConditionalOnProperty(name = "agent.skills-enabled", havingValue = "true", matchIfMissing = true)
    public SkillRegistryImpl skillRegistry(SkillLoader skillLoader, AgentProperties agentProperties) {
        return new SkillRegistryImpl(skillLoader, agentProperties);
    }

    // ==================== 工具与 Agent ====================

    @Bean
    @ConditionalOnMissingBean(ToolGateway.class)
    public ToolGatewayImpl toolGateway(List<ToolExecutor> executorList, ToolPermissionChecker permissionChecker,
                                       MetricsRecorder metrics, AgentProperties properties) {
        // 统一工具执行超时兜底 + 错误分类（C3）：超时秒数与输出截断长度取自 agent.security.*
        AgentProperties.ToolSecurityConfig security = properties.getSecurity();
        return new ToolGatewayImpl(executorList, permissionChecker, metrics,
                security.getToolTimeoutSeconds(), security.getMaxOutputLength());
    }

    @Bean
    @ConditionalOnMissingBean(AgentGateway.class)
    public AgentGatewayImpl agentGateway(AgentProperties agentProperties,
                                         LongTermMemoryGateway longTermMemoryGateway,
                                         AgentRegistryLoader agentRegistryLoader) {
        return new AgentGatewayImpl(agentProperties, longTermMemoryGateway, agentRegistryLoader);
    }

    // ==================== 存储后端（file | jdbc | redis 三选一） ====================

    @Configuration
    @ConditionalOnProperty(name = "agent.storage.type", havingValue = "file", matchIfMissing = true)
    public static class FileStorageConfiguration {

        @Bean
        @ConditionalOnMissingBean(MemoryGateway.class)
        public FileBasedSessionGateway fileBasedSessionGateway(AgentProperties properties) {
            return new FileBasedSessionGateway(properties);
        }

        @Bean
        @ConditionalOnMissingBean(LongTermMemoryGateway.class)
        public FileBasedMemoryGateway fileBasedMemoryGateway(AgentProperties properties) {
            return new FileBasedMemoryGateway(properties);
        }

        @Bean
        @ConditionalOnMissingBean(MemoryPageStore.class)
        public FileMemoryPageStore fileMemoryPageStore(AgentProperties properties) {
            return new FileMemoryPageStore(properties);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "agent.storage.type", havingValue = "jdbc")
    public static class JdbcStorageConfiguration {

        @Bean
        @ConditionalOnMissingBean(MemoryGateway.class)
        public JdbcSessionGateway jdbcSessionGateway(JdbcTemplate jdbc) {
            return new JdbcSessionGateway(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean(LongTermMemoryGateway.class)
        public JdbcLongTermMemoryGateway jdbcLongTermMemoryGateway(JdbcTemplate jdbc) {
            return new JdbcLongTermMemoryGateway(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean(MemoryPageStore.class)
        public JdbcMemoryPageStore jdbcMemoryPageStore(JdbcTemplate jdbc) {
            return new JdbcMemoryPageStore(jdbc);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "agent.storage.type", havingValue = "redis")
    public static class RedisStorageConfiguration {

        @Bean
        @ConditionalOnMissingBean(MemoryGateway.class)
        public RedisSessionGateway redisSessionGateway(StringRedisTemplate redis) {
            return new RedisSessionGateway(redis);
        }

        @Bean
        @ConditionalOnMissingBean(LongTermMemoryGateway.class)
        public RedisLongTermMemoryGateway redisLongTermMemoryGateway(StringRedisTemplate redis) {
            return new RedisLongTermMemoryGateway(redis);
        }

        @Bean
        @ConditionalOnMissingBean(MemoryPageStore.class)
        public RedisMemoryPageStore redisMemoryPageStore(StringRedisTemplate redis) {
            return new RedisMemoryPageStore(redis);
        }
    }

    // ==================== 会话锁（local | redis 二选一） ====================

    @Configuration
    @ConditionalOnProperty(name = "agent.storage.lock-type", havingValue = "local", matchIfMissing = true)
    public static class LocalLockConfiguration {

        @Bean
        @ConditionalOnMissingBean(com.mwb.ai.claw.infrastructure.collaboration.SessionLockManager.class)
        public LocalSessionLockManager localSessionLockManager() {
            return new LocalSessionLockManager();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "agent.storage.lock-type", havingValue = "redis")
    public static class RedisLockConfiguration {

        @Bean
        @ConditionalOnMissingBean(com.mwb.ai.claw.infrastructure.collaboration.SessionLockManager.class)
        public RedisSessionLockManager redisSessionLockManager(StringRedisTemplate redis) {
            return new RedisSessionLockManager(redis);
        }
    }
}
