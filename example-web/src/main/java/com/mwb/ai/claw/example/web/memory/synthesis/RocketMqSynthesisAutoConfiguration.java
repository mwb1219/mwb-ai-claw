package com.mwb.ai.claw.example.web.memory.synthesis;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mwb.ai.claw.domain.memory.model.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesizer;
import com.mwb.ai.claw.domain.memory.synthesize.MemorySynthesisDispatcher;
import com.mwb.ai.claw.domain.memory.store.MemoryPageStore;
import com.mwb.ai.claw.infrastructure.observability.MetricsRecorder;

/**
 * Phase 3 RocketMQ 提炼任务队列自动装配（example-web 扩展）。
 * <p>
 * 生效条件：
 * <ul>
 *   <li>{@code agent.memory.synthesis-queue-type=rocketmq}</li>
 *   <li>RocketMQ {@link DefaultMQProducer} Bean 可用（rocketmq-spring-boot-starter 自动装配）</li>
 *   <li>{@link JdbcTemplate} 可用（staging 表为 DB 表）</li>
 * </ul>
 * <p>
 * 装配优先级：{@code @Primary} 覆盖框架默认的 Phase 1/2 {@link MemorySynthesisDispatcher} 实现。
 * 未启用时（默认）框架自动装配的 {@code LockFreeMemorySynthesisDispatcher}（Phase 2）或
 * {@code LockMemorySynthesisDispatcher}（Phase 1）正常工作，不依赖 RocketMQ 依赖。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(DefaultMQProducer.class)
@ConditionalOnProperty(prefix = "agent.memory", name = "synthesis-queue-type", havingValue = "rocketmq")
@ConditionalOnBean({DefaultMQProducer.class, JdbcTemplate.class})
public class RocketMqSynthesisAutoConfiguration {

    @Bean
    public JdbcSnapshotStaging jdbcSnapshotStaging(JdbcTemplate jdbcTemplate) {
        return new JdbcSnapshotStaging(jdbcTemplate);
    }

    @Bean
    @Primary
    public RocketMqMemorySynthesisDispatcher rocketMqMemorySynthesisDispatcher(
            DefaultMQProducer producer,
            SnapshotStaging staging,
            LayeredMemoryConfig config,
            MemorySynthesizer synthesizer,
            MetricsRecorder metrics,
            MemoryPageStore pageStore) {
        RocketMqMemorySynthesisDispatcher.MemoryPageStoreAccessor accessor =
                new RocketMqMemorySynthesisDispatcher.DefaultMemoryPageStoreAccessor(pageStore);
        return new RocketMqMemorySynthesisDispatcher(producer, staging, config, synthesizer, metrics, accessor);
    }

    @Bean
    public RocketMqSynthesisConsumer rocketMqSynthesisConsumer(
            RocketMqMemorySynthesisDispatcher dispatcher) {
        return new RocketMqSynthesisConsumer(dispatcher);
    }
}
