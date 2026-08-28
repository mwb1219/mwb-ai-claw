package com.mwb.ai.claw.infrastructure.autoconfigure;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
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
import com.mwb.ai.claw.infrastructure.lock.DistributedLock;
import com.mwb.ai.claw.infrastructure.lock.RedisDistributedLock;
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
import com.mwb.ai.claw.infrastructure.memory.storage.redis.RedisMemoryIndexer;
import com.mwb.ai.claw.infrastructure.memory.storage.redis.RedisMemorySearchable;
import com.mwb.ai.claw.infrastructure.memory.strategy.LlmMemorySynthesizer;
import com.mwb.ai.claw.infrastructure.memory.synthesis.LocalSynthesisCache;
import com.mwb.ai.claw.infrastructure.memory.synthesis.MemorySynthesisExecutor;
import com.mwb.ai.claw.infrastructure.memory.synthesis.RedisSynthesisCache;
import com.mwb.ai.claw.infrastructure.memory.synthesis.SynthesisCache;
import com.mwb.ai.claw.infrastructure.observability.JdbcRunUsageStore;
import com.mwb.ai.claw.infrastructure.observability.JdbcTraceStore;
import com.mwb.ai.claw.infrastructure.observability.LocalRunUsageStore;
import com.mwb.ai.claw.infrastructure.observability.LocalTraceStore;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;
import com.mwb.ai.claw.infrastructure.rag.access.AllowAllRagAccessPolicy;
import com.mwb.ai.claw.infrastructure.redis.RedisSearchTemplate;
import com.mwb.ai.claw.infrastructure.rag.context.DefaultRagContextProvider;
import com.mwb.ai.claw.infrastructure.rag.embed.OpenAiRagEmbeddingGateway;
import com.mwb.ai.claw.infrastructure.rag.retrieve.DefaultRagRetrievalService;
import com.mwb.ai.claw.infrastructure.rag.store.FileRagDocumentStore;
import com.mwb.ai.claw.infrastructure.rag.store.JdbcRagDocumentStore;
import com.mwb.ai.claw.infrastructure.rag.store.LocalRagIndexStore;
import com.mwb.ai.claw.infrastructure.rag.store.RedisRagIndexStore;
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

    // ==================== 提炼缓存（本地 JVM LRU，默认兜底，单实例 / storage=file 形态） ====================

    @Configuration
    public static class LocalSynthesisCacheConfiguration {
        @Bean
        @ConditionalOnMissingBean(SynthesisCache.class)
        public LocalSynthesisCache localSynthesisCache(AgentProperties properties) {
            return new LocalSynthesisCache(properties);
        }
    }

    // ==================== 提炼缓存（Redis 分布式，storage=db 多实例生产形态） ====================
    // 启用条件：
    //   1) agent.memory.synthesis-cache-type = redis，或
    //   2) agent.memory.synthesis-cache-type = auto && agent.storage.type = db；
    //   且 classpath 含 spring-data-redis（starter-data-redis 为 optional 依赖，
    //   需使用方自行引入后自动启用。无 Redis 时仍保留本地兜底，不报错）。

    @Configuration
    @ConditionalOnClass(RedisConnectionFactory.class)
    @Conditional(SynthesisCacheRedisEffectiveCondition.class)
    public static class RedisSynthesisCacheConfiguration {

        @Bean
        @ConditionalOnMissingBean(SynthesisCache.class)
        public RedisSynthesisCache redisSynthesisCache(
                ObjectProvider<RedisConnectionFactory> factoryProvider, AgentProperties properties) {
            RedisConnectionFactory factory = factoryProvider.getIfAvailable(() -> synthesisRedisFactory(properties));
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            return new RedisSynthesisCache(template, properties);
        }

        /** 未显式声明 spring-data-redis 连接工厂时：优先使用 synthesis 独立 redisUri，否则复用 lock redisUri 兜底 */
        private RedisConnectionFactory synthesisRedisFactory(AgentProperties properties) {
            String uriStr = properties.getMemory().getSynthesisCacheRedisUri();
            if (uriStr == null || uriStr.trim().isEmpty()) {
                uriStr = properties.getCollaboration().getLock().getRedisUri();
            }
            RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration();
            RedisURI uri = RedisURI.create(uriStr);
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

    /** 自定义 Condition：解析 synthesis-cache-type 生效值。*/
    public static class SynthesisCacheRedisEffectiveCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            String type = env.getProperty("agent.memory.synthesis-cache-type", "auto");
            String storage = env.getProperty("agent.storage.type", "file");
            String effective = "auto".equalsIgnoreCase(type)
                    ? ("db".equalsIgnoreCase(storage) ? "redis" : "local")
                    : type;
            return "redis".equalsIgnoreCase(effective);
        }
    }

    @Bean
    @ConditionalOnMissingBean(LayeredMemoryGateway.class)
    public LayeredMemoryGatewayImpl layeredMemoryGateway(AgentProperties properties,
                                                         MemoryPageStore pageStore,
                                                         MemorySynthesizer synthesizer,
                                                         MemoryRetriever retriever,
                                                         com.mwb.ai.claw.domain.memory.synthesize.SynthesisTaskQueue taskQueue) {
        return new LayeredMemoryGatewayImpl(properties, pageStore, synthesizer, retriever, taskQueue);
    }

    // ==================== 提炼任务队列（Phase 1：本地兜底，单实例 / storage=file） ====================

    @Configuration
    public static class LocalSynthesisTaskQueueConfiguration {

        @Bean
        @ConditionalOnMissingBean(com.mwb.ai.claw.domain.memory.synthesize.SynthesisTaskQueue.class)
        public com.mwb.ai.claw.infrastructure.memory.synthesis.LocalSynthesisTaskQueue localSynthesisTaskQueue(
                MemorySynthesisExecutor executor) {
            return new com.mwb.ai.claw.infrastructure.memory.synthesis.LocalSynthesisTaskQueue(executor);
        }
    }

    // ==================== 提炼任务队列（Phase 1：Redis 分布式锁，storage=db 多实例） ====================
    // 启用条件：
    //   1) agent.memory.synthesis-queue-type = redis，或
    //   2) agent.memory.synthesis-queue-type = auto && agent.storage.type = db；
    //   且 classpath 含 spring-data-redis（starter-data-redis 为 optional 依赖，需使用方自行引入）。

    @Configuration
    @ConditionalOnClass(RedisConnectionFactory.class)
    @Conditional(SynthesisQueueRedisEffectiveCondition.class)
    public static class LockSynthesisTaskQueueConfiguration {

        @Bean
        @ConditionalOnMissingBean(com.mwb.ai.claw.domain.memory.synthesize.SynthesisTaskQueue.class)
        public com.mwb.ai.claw.infrastructure.memory.synthesis.LockSynthesisTaskQueue lockSynthesisTaskQueue(
                DistributedLock distributedLock,
                AgentProperties properties,
                MetricsRecorder metrics,
                MemorySynthesisExecutor executor) {
            return new com.mwb.ai.claw.infrastructure.memory.synthesis.LockSynthesisTaskQueue(
                    distributedLock, properties.getMemory(), metrics, executor);
        }
    }

    /** 自定义 Condition：解析 synthesis-queue-type 生效值。*/
    public static class SynthesisQueueRedisEffectiveCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            String type = env.getProperty("agent.memory.synthesis-queue-type", "auto");
            String storage = env.getProperty("agent.storage.type", "file");
            String effective = "auto".equalsIgnoreCase(type)
                    ? ("db".equalsIgnoreCase(storage) ? "redis" : "local")
                    : type;
            return "redis".equalsIgnoreCase(effective);
        }
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

        // 文档元数据存储随生效 provider 切换：effective=local → 文件版；其余（redis）→ JDBC 版。
        // 仅当业务侧未注册自定义 RagDocumentStore 时装配。
        @Bean
        @ConditionalOnMissingBean(RagDocumentStore.class)
        @Conditional(RagDocumentStoreFileCondition.class)
        public FileRagDocumentStore fileRagDocumentStore(RagConfig config) {
            return new FileRagDocumentStore(config);
        }

        @Bean
        @ConditionalOnMissingBean(RagDocumentStore.class)
        @Conditional(RagDocumentStoreDbCondition.class)
        public JdbcRagDocumentStore jdbcRagDocumentStore(JdbcTemplate jdbc) {
            return new JdbcRagDocumentStore(jdbc);
        }

        @Bean
        @ConditionalOnMissingBean(RagIndexStore.class)
        @Conditional(RagIndexStoreLocalCondition.class)
        public LocalRagIndexStore ragIndexStore(RagConfig config) {
            return new LocalRagIndexStore(config);
        }

        @Bean
        @ConditionalOnMissingBean(RagIndexStore.class)
        @Conditional(RagIndexStoreRedisCondition.class)
        public RedisRagIndexStore redisRagIndexStore(JdbcTemplate jdbc, RedisSearchTemplate redisSearchTemplate) {
            return new RedisRagIndexStore(jdbc, redisSearchTemplate);
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

    // ==================== RAG 生效 provider 装配条件 ====================
    // agent.rag.provider 支持 auto（默认，跟随 agent.storage.type：file→local，db→redis）与
    // 显式 redis（高级用法，语义与 auto+db 一致）。内置不再提供 jdbc / pgvector 实现，
    // 其它 provider 值由业务方自定义 RagIndexStore 扩展 Bean 表达。单一 @ConditionalOnProperty
    // 无法表达「auto + storage」组合，故用自定义 Condition 解析「生效 provider」后按值匹配。

    /** 解析生效 provider：auto → 跟随 storage.type，否则取显式值。 */
    public abstract static class RagEffectiveProviderCondition implements Condition {

        @Override
        public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            String provider = env.getProperty("agent.rag.provider", "auto");
            String storage = env.getProperty("agent.storage.type", "file");
            String effective = "auto".equalsIgnoreCase(provider)
                    ? ("db".equalsIgnoreCase(storage) ? "redis" : "local")
                    : provider;
            return matchesEffective(effective);
        }

        /** 子类判断是否匹配当前生效 provider。 */
        protected abstract boolean matchesEffective(String effective);
    }

    /** 生效 provider = local（provider=local，或 auto + storage=file）。 */
    public static class RagIndexStoreLocalCondition extends RagEffectiveProviderCondition {
        @Override
        protected boolean matchesEffective(String effective) {
            return "local".equals(effective);
        }
    }

    /** 生效 provider = redis（provider=redis，或 auto + storage=db，内置推荐形态）。 */
    public static class RagIndexStoreRedisCondition extends RagEffectiveProviderCondition {
        @Override
        protected boolean matchesEffective(String effective) {
            return "redis".equals(effective);
        }
    }

    /** 文档存储走本地文件：生效 provider = local。 */
    public static class RagDocumentStoreFileCondition extends RagEffectiveProviderCondition {
        @Override
        protected boolean matchesEffective(String effective) {
            return "local".equals(effective);
        }
    }

    /** 文档存储走 JDBC：生效 provider = redis（业务数据落 MySQL）。 */
    public static class RagDocumentStoreDbCondition extends RagEffectiveProviderCondition {
        @Override
        protected boolean matchesEffective(String effective) {
            return "redis".equals(effective);
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
        public JdbcMemoryPageStore jdbcMemoryPageStore(JdbcTemplate jdbc,
                                                       ObjectProvider<RedisMemoryIndexer> indexer,
                                                       ObjectProvider<RedisMemorySearchable> searchable) {
            // Redis 检索能力为可选（无 spring-data-redis 时双写跳过、召回委托返回空，回退应用层打分）。
            // 委托用具体类型 RedisMemorySearchable 而非 MemorySearchable：JdbcMemoryPageStore 自身
            // 即实现 MemorySearchable（供召回策略 instanceof 判断做下推），按接口注入会自我解析造成循环依赖。
            return new JdbcMemoryPageStore(jdbc, indexer.getIfAvailable(), searchable.getIfAvailable());
        }

        /** Memory Redis 双写器（classpath 含 spring-data-redis 时装配）。 */
        @Bean
        @ConditionalOnMissingBean(RedisMemoryIndexer.class)
        @ConditionalOnClass(RedisConnectionFactory.class)
        public RedisMemoryIndexer redisMemoryIndexer(RedisSearchTemplate redisSearchTemplate,
                                                     EmbeddingGateway embeddingGateway,
                                                     AgentProperties properties) {
            return new RedisMemoryIndexer(redisSearchTemplate, embeddingGateway, properties.getMemory());
        }

        /** Memory 检索下推 SPI 的 Redis 实现（JdbcMemoryPageStore 委托的召回执行体，业务方可整体替换）。 */
        @Bean
        @ConditionalOnMissingBean(RedisMemorySearchable.class)
        @ConditionalOnClass(RedisConnectionFactory.class)
        public RedisMemorySearchable redisMemorySearchable(RedisSearchTemplate redisSearchTemplate) {
            return new RedisMemorySearchable(redisSearchTemplate);
        }
    }

    // ==================== Redis 检索索引基础设施（Redis Stack / RediSearch） ====================
    // Memory 与 RAG 召回共用：连接优先复用业务方 spring.data.redis.* 自动装配的
    // RedisConnectionFactory；未配置时兜底与会话锁同源（agent.collaboration.lock.redisUri）创建。
    // classpath 无 spring-data-redis 时不装配（db 形态召回回退应用层打分）。

    @Configuration
    @ConditionalOnClass(RedisConnectionFactory.class)
    public static class RedisSearchConfiguration {

        @Bean
        @ConditionalOnMissingBean(RedisSearchTemplate.class)
        public RedisSearchTemplate redisSearchTemplate(
                ObjectProvider<RedisConnectionFactory> factoryProvider, AgentProperties properties) {
            RedisConnectionFactory factory = factoryProvider.getIfAvailable(() -> fallbackFactory(properties));
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            return new RedisSearchTemplate(template, properties.getRedis().getIndexPrefix());
        }

        private RedisConnectionFactory fallbackFactory(AgentProperties properties) {
            RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration();
            RedisURI uri = RedisURI.create(properties.getCollaboration().getLock().getRedisUri());
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

    // ==================== 会话锁（本地 JVM 实现，单实例部署） ====================

    @Configuration
    public static class LocalLockConfiguration {

        @Bean
        @ConditionalOnMissingBean(com.mwb.ai.claw.infrastructure.collaboration.lock.SessionLockManager.class)
        public LocalSessionLockManager localSessionLockManager() {
            return new LocalSessionLockManager();
        }
    }

    // ==================== 分布式锁基础设施（Redis 实现，会话锁 / 合成锁共用） ====================
    // 装配条件：classpath 含 spring-data-redis，且会话锁或合成锁任一启用 Redis 形态。
    // 单独抽取为公共 bean，供 RedisSessionLockManager 与 LockSynthesisTaskQueue 复用，
    // 统一 Lua 脚本释放/续期、token 管理与 watchdog 续期调度，消除两处重复实现。

    @Configuration
    @ConditionalOnClass(RedisConnectionFactory.class)
    @Conditional(RedisLockNeededCondition.class)
    public static class RedisDistributedLockConfiguration {

        @Bean
        @ConditionalOnMissingBean(DistributedLock.class)
        public RedisDistributedLock redisDistributedLock(
                ObjectProvider<RedisConnectionFactory> factoryProvider, AgentProperties properties) {
            RedisConnectionFactory factory = factoryProvider.getIfAvailable(() -> distributedLockRedisFactory(properties));
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            return new RedisDistributedLock(template);
        }

        /** 未显式声明 spring-data-redis 连接工厂时：优先使用 synthesis cache redisUri，否则复用 lock redisUri 兜底 */
        private RedisConnectionFactory distributedLockRedisFactory(AgentProperties properties) {
            String uriStr = properties.getMemory().getSynthesisCacheRedisUri();
            if (uriStr == null || uriStr.trim().isEmpty()) {
                uriStr = properties.getCollaboration().getLock().getRedisUri();
            }
            RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration();
            RedisURI uri = RedisURI.create(uriStr);
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

    /** 自定义 Condition：会话锁或合成锁任一启用 Redis 形态时，才装配分布式锁基础设施。 */
    public static class RedisLockNeededCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            // 会话锁：agent.collaboration.lock.type=redis
            String lockType = env.getProperty("agent.collaboration.lock.type", "local");
            if ("redis".equalsIgnoreCase(lockType)) {
                return true;
            }
            // 合成锁：synthesis-queue-type 生效为 redis
            String queueType = env.getProperty("agent.memory.synthesis-queue-type", "auto");
            String storage = env.getProperty("agent.storage.type", "file");
            String effectiveQueue = "auto".equalsIgnoreCase(queueType)
                    ? ("db".equalsIgnoreCase(storage) ? "redis" : "local") : queueType;
            return "redis".equalsIgnoreCase(effectiveQueue);
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
        public RedisSessionLockManager redisSessionLockManager(
                DistributedLock distributedLock, AgentProperties properties) {
            return new RedisSessionLockManager(distributedLock,
                    properties.getCollaboration().getLock());
        }
    }
}
