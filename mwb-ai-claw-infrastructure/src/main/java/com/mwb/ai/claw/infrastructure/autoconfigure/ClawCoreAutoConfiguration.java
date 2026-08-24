package com.mwb.ai.claw.infrastructure.autoconfigure;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.ClassUtils;

import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.domain.llm.EmbeddingGateway;
import com.mwb.ai.claw.domain.llm.LlmGateway;
import com.mwb.ai.claw.domain.memory.gateway.LayeredMemoryGateway;
import com.mwb.ai.claw.domain.memory.gateway.LongTermMemoryGateway;
import com.mwb.ai.claw.domain.memory.gateway.MemoryGateway;
import com.mwb.ai.claw.domain.memory.retrieve.MemoryRetriever;
import com.mwb.ai.claw.domain.memory.store.MemoryPageStore;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesizer;
import com.mwb.ai.claw.domain.observability.RunUsageStore;
import com.mwb.ai.claw.domain.observability.TraceStore;
import com.mwb.ai.claw.domain.rag.access.RagAccessPolicy;
import com.mwb.ai.claw.domain.rag.config.RagConfig;
import com.mwb.ai.claw.domain.rag.context.RagContextProvider;
import com.mwb.ai.claw.domain.rag.embed.RagEmbeddingGateway;
import com.mwb.ai.claw.domain.rag.retrieve.RagReranker;
import com.mwb.ai.claw.domain.rag.retrieve.RagRetrievalService;
import com.mwb.ai.claw.domain.rag.store.RagDocumentStore;
import com.mwb.ai.claw.domain.rag.store.RagIndexStore;
import com.mwb.ai.claw.domain.rag.write.RagChunker;
import com.mwb.ai.claw.domain.rag.write.RagDocumentParser;
import com.mwb.ai.claw.domain.rag.write.RagIngestionService;
import com.mwb.ai.claw.domain.skill.SkillGateway;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolGateway;
import com.mwb.ai.claw.domain.tool.ToolPermissionChecker;
import com.mwb.ai.claw.infrastructure.auth.ConfigToolPermissionChecker;
import com.mwb.ai.claw.infrastructure.collaboration.lock.LocalSessionLockManager;
import com.mwb.ai.claw.infrastructure.collaboration.lock.RedisSessionLockManager;
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
import com.mwb.ai.claw.infrastructure.memory.gateway.LayeredMemoryGatewayImpl;
import com.mwb.ai.claw.infrastructure.memory.storage.file.FileBasedMemoryGateway;
import com.mwb.ai.claw.infrastructure.memory.storage.file.FileBasedSessionGateway;
import com.mwb.ai.claw.infrastructure.memory.storage.file.FileMemoryPageStore;
import com.mwb.ai.claw.infrastructure.memory.storage.jdbc.JdbcLongTermMemoryGateway;
import com.mwb.ai.claw.infrastructure.memory.storage.jdbc.JdbcMemoryPageStore;
import com.mwb.ai.claw.infrastructure.memory.storage.jdbc.JdbcSessionGateway;
import com.mwb.ai.claw.infrastructure.memory.strategy.LlmMemorySynthesizer;
import com.mwb.ai.claw.infrastructure.memory.synthesis.MemorySynthesisExecutor;
import com.mwb.ai.claw.infrastructure.memory.synthesis.SynthesisCache;
import com.mwb.ai.claw.infrastructure.observability.JdbcRunUsageStore;
import com.mwb.ai.claw.infrastructure.observability.JdbcTraceStore;
import com.mwb.ai.claw.infrastructure.observability.LocalRunUsageStore;
import com.mwb.ai.claw.infrastructure.observability.LocalTraceStore;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;
import com.mwb.ai.claw.infrastructure.rag.access.AllowAllRagAccessPolicy;
import com.mwb.ai.claw.infrastructure.rag.context.DefaultRagContextProvider;
import com.mwb.ai.claw.infrastructure.rag.embed.OpenAiRagEmbeddingGateway;
import com.mwb.ai.claw.infrastructure.rag.retrieve.DefaultRagRetrievalService;
import com.mwb.ai.claw.infrastructure.rag.store.FileRagDocumentStore;
import com.mwb.ai.claw.infrastructure.rag.store.LocalRagIndexStore;
import com.mwb.ai.claw.infrastructure.rag.store.PgVectorRagIndexStore;
import com.mwb.ai.claw.infrastructure.rag.write.DefaultRagIngestionService;
import com.mwb.ai.claw.infrastructure.rag.write.MultiFormatRagDocumentParser;
import com.mwb.ai.claw.infrastructure.rag.write.PdfRagDocumentParser;
import com.mwb.ai.claw.infrastructure.rag.write.TextRagChunker;
import com.mwb.ai.claw.infrastructure.rag.write.TextRagDocumentParser;
import com.mwb.ai.claw.infrastructure.rag.write.WordRagDocumentParser;
import com.mwb.ai.claw.infrastructure.skill.SkillLoader;
import com.mwb.ai.claw.infrastructure.skill.SkillRegistryImpl;
import com.mwb.ai.claw.infrastructure.tool.ToolGatewayImpl;

import io.lettuce.core.RedisURI;
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
 *   <li>存储后端二选一：{@code agent.storage.type}=file（默认）| db，分别注册
 *       会话 / 长期记忆 / 记忆页三组实现（db 走 JDBC 持久化）；</li>
 *   <li>会话锁固定本地 JVM 实现（单实例部署）；</li>
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

    // ==================== 步骤级 trace 存储（local | db 二选一） ====================
    // 开关：agent.observability.trace.enabled=false 时整块不装配（无 TraceStore Bean，ChatCmdExe 经
    // ObjectProvider 空安全降级）；store=db 且 classpath 含 JdbcTemplate 时落库，否则本地 JSON 文件（默认）。

    @Configuration
    @ConditionalOnProperty(prefix = "agent.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public static class TraceStorageConfiguration {

        @Bean
        @ConditionalOnMissingBean(TraceStore.class)
        @ConditionalOnProperty(prefix = "agent.observability.trace", name = "store", havingValue = "db")
        @ConditionalOnClass(JdbcTemplate.class)
        public JdbcTraceStore jdbcTraceStore(JdbcTemplate jdbc) {
            return new JdbcTraceStore(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean(TraceStore.class)
        public LocalTraceStore localTraceStore(AgentProperties properties) {
            return new LocalTraceStore(properties);
        }
    }

    // ==================== 运行用量摘要存储（local | db 二选一） ====================
    // agent.observability.run-usage-log=false 时由 RunUsageRecorder 空安全跳过；
    // run-usage-store=db 且 classpath 含 JdbcTemplate 时落 claw_run_usage 表，否则本地 JSONL（默认）。

    @Configuration
    public static class RunUsageStorageConfiguration {

        @Bean
        @ConditionalOnMissingBean(RunUsageStore.class)
        @ConditionalOnProperty(prefix = "agent.observability", name = "run-usage-store", havingValue = "db")
        @ConditionalOnClass(JdbcTemplate.class)
        public JdbcRunUsageStore jdbcRunUsageStore(JdbcTemplate jdbc) {
            return new JdbcRunUsageStore(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean(RunUsageStore.class)
        public LocalRunUsageStore localRunUsageStore(AgentProperties properties) {
            return new LocalRunUsageStore(properties);
        }
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

    // ==================== 独立 RAG ====================

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "agent.rag.enabled", havingValue = "true")
    public static class RagConfiguration {

        @Bean
        @ConditionalOnMissingBean(RagConfig.class)
        public RagConfig ragConfig(AgentProperties properties) {
            return properties.getRag();
        }

        @Bean
        @ConditionalOnMissingBean(RagDocumentParser.class)
        public MultiFormatRagDocumentParser ragDocumentParser() {
            // 具体解析器在组合器内部按 classpath 探测构建，避免与 @ConditionalOnMissingBean 顺序冲突；
            // 未引入 PDFBox / POI 时对应解析器为 null，组合器退化为纯文本解析（与旧行为一致）。
            ClassLoader loader = MultiFormatRagDocumentParser.class.getClassLoader();
            RagDocumentParser pdfParser = ClassUtils.isPresent(
                    "org.apache.pdfbox.pdmodel.PDDocument", loader) ? new PdfRagDocumentParser() : null;
            RagDocumentParser wordParser = ClassUtils.isPresent(
                    "org.apache.poi.xwpf.usermodel.XWPFDocument", loader) ? new WordRagDocumentParser() : null;
            return new MultiFormatRagDocumentParser(new TextRagDocumentParser(), pdfParser, wordParser);
        }

        @Bean
        @ConditionalOnMissingBean(RagAccessPolicy.class)
        public AllowAllRagAccessPolicy ragAccessPolicy() {
            return new AllowAllRagAccessPolicy();
        }

        @Bean
        @ConditionalOnMissingBean(RagChunker.class)
        public TextRagChunker ragChunker(RagConfig config) {
            return new TextRagChunker(config);
        }

        @Bean
        @ConditionalOnMissingBean(RagEmbeddingGateway.class)
        public OpenAiRagEmbeddingGateway ragEmbeddingGateway(
                RagConfig config, org.springframework.web.client.RestTemplate restTemplate) {
            return new OpenAiRagEmbeddingGateway(config, restTemplate);
        }

        // 文档元数据存储与向量索引 provider 正交：local（本地 JSON）与 pgvector（Postgres 向量索引）共用
        // 文件版元数据存储，仅当业务侧未注册自定义 RagDocumentStore 时装配。
        @Bean
        @ConditionalOnMissingBean(RagDocumentStore.class)
        public FileRagDocumentStore ragDocumentStore(RagConfig config) {
            return new FileRagDocumentStore(config);
        }

        @Bean
        @ConditionalOnMissingBean(RagIndexStore.class)
        @ConditionalOnProperty(name = "agent.rag.provider", havingValue = "local", matchIfMissing = true)
        public LocalRagIndexStore ragIndexStore(RagConfig config) {
            return new LocalRagIndexStore(config);
        }

        @Bean
        @ConditionalOnMissingBean(RagIndexStore.class)
        @ConditionalOnProperty(name = "agent.rag.provider", havingValue = "pgvector")
        public PgVectorRagIndexStore pgVectorRagIndexStore(RagConfig config, JdbcTemplate jdbc) {
            return new PgVectorRagIndexStore(jdbc, config);
        }

        @Bean
        @ConditionalOnMissingBean(RagIngestionService.class)
        public DefaultRagIngestionService ragIngestionService(
                RagDocumentParser parser,
                RagChunker chunker,
                RagEmbeddingGateway embeddingGateway,
                RagIndexStore indexStore,
                RagDocumentStore documentStore,
                RagConfig config) {
            return new DefaultRagIngestionService(
                    parser, chunker, embeddingGateway, indexStore, documentStore, config);
        }

        @Bean
        @ConditionalOnMissingBean(RagRetrievalService.class)
        public DefaultRagRetrievalService ragRetrievalService(
                RagEmbeddingGateway embeddingGateway,
                RagIndexStore indexStore,
                RagDocumentStore documentStore,
                ObjectProvider<RagReranker> rerankerProvider,
                RagConfig config) {
            return new DefaultRagRetrievalService(
                    embeddingGateway, indexStore, documentStore, rerankerProvider.getIfAvailable(), config);
        }

        @Bean
        @ConditionalOnMissingBean(RagContextProvider.class)
        public DefaultRagContextProvider ragContextProvider(
                RagRetrievalService retrievalService, RagConfig config) {
            return new DefaultRagContextProvider(retrievalService, config);
        }
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

    // ==================== 存储后端（file | db 二选一） ====================

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
    @ConditionalOnProperty(name = "agent.storage.type", havingValue = "db")
    public static class DbStorageConfiguration {

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

    // ==================== 会话锁（本地 JVM 实现，单实例部署） ====================

    @Configuration
    public static class LocalLockConfiguration {

        @Bean
        @ConditionalOnMissingBean(com.mwb.ai.claw.infrastructure.collaboration.lock.SessionLockManager.class)
        public LocalSessionLockManager localSessionLockManager() {
            return new LocalSessionLockManager();
        }
    }

    // ==================== 分布式会话锁（Redis 实现，多实例部署） ====================
    // 启用条件：agent.collaboration.lock.type=redis 且 classpath 含 spring-data-redis
    // （starter-data-redis 为 optional 依赖，需使用方自行引入后自动启用）

    @Configuration
    @ConditionalOnProperty(prefix = "agent.collaboration.lock", name = "type", havingValue = "redis")
    @ConditionalOnClass(RedisConnectionFactory.class)
    public static class RedisLockConfiguration {

        @Bean
        @ConditionalOnMissingBean(com.mwb.ai.claw.infrastructure.collaboration.lock.SessionLockManager.class)
        public RedisSessionLockManager redisSessionLockManager(AgentProperties properties) {
            AgentProperties.LockConfig cfg = properties.getCollaboration().getLock();
            StringRedisTemplate template = new StringRedisTemplate(redisConnectionFactory(cfg));
            template.afterPropertiesSet();
            return new RedisSessionLockManager(template, cfg);
        }

        private RedisConnectionFactory redisConnectionFactory(AgentProperties.LockConfig cfg) {
            RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration();
            RedisURI uri = RedisURI.create(cfg.getRedisUri());
            standalone.setHostName(uri.getHost());
            standalone.setPort(uri.getPort());
            if (uri.getPassword() != null) {
                standalone.setPassword(uri.getPassword());
            }
            if (uri.getDatabase() != 0) {
                standalone.setDatabase(uri.getDatabase());
            }
            LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone);
            factory.afterPropertiesSet();
            return factory;
        }
    }
}
