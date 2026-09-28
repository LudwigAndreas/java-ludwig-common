package ru.ludwigandreas.messaging.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.NoopAuditSink;
import ru.ludwigandreas.messaging.api.EnvelopeReader;
import ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder;
import ru.ludwigandreas.messaging.metrics.ConsumerActivityMonitor;
import ru.ludwigandreas.messaging.metrics.MessagingMetrics;
import ru.ludwigandreas.messaging.metrics.NoopMessagingMetrics;
import ru.ludwigandreas.messaging.settings.DedupTransactionPolicy;
import ru.ludwigandreas.messaging.settings.MessagingProperties;

/**
 * The shared consumer wiring, when spring-kafka is present and a deployment has not switched it off.
 *
 * <p>Gated the way all three wirings this module replaces already were - {@code @ConditionalOnClass} on
 * {@code KafkaListener} plus a property - so a deployment with no broker resolves none of it. Nothing here
 * declares a listener container factory of its own: a consumer asks
 * {@link ListenerContainerFactoryBuilder} for one at the type its payload is and registers it under its
 * own bean name, which is what removes the wildcard-and-cast that
 * {@code UserSettingsKafkaAutoConfiguration} needed.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass(KafkaListener.class)
@ConditionalOnProperty(prefix = MessagingProperties.PREFIX, name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(MessagingProperties.class)
public class MessagingAutoConfiguration {

    /**
     * Reads the producer's envelope back off a record.
     *
     * @param properties this module's configuration, for the correlation header's name
     * @return the reader
     */
    @Bean
    @ConditionalOnMissingBean(EnvelopeReader.class)
    public EnvelopeReader ludwigEnvelopeReader(MessagingProperties properties) {
        return new EnvelopeReader(properties.getCorrelationHeader());
    }

    /**
     * The no-op instrumentation, replaced by the Micrometer binding when one is possible.
     *
     * @return metrics that do nothing
     */
    @Bean
    @ConditionalOnMissingBean(MessagingMetrics.class)
    public MessagingMetrics ludwigMessagingMetrics() {
        return new NoopMessagingMetrics();
    }

    /**
     * What notices a topic has gone quiet.
     *
     * @param metrics where the gauge goes
     * @param clock   the clock
     * @return the monitor, which is also a {@code RecordInterceptor}
     */
    @Bean
    @ConditionalOnMissingBean(ConsumerActivityMonitor.class)
    public ConsumerActivityMonitor ludwigConsumerActivityMonitor(MessagingMetrics metrics,
                                                                 ObjectProvider<Clock> clock) {
        return new ConsumerActivityMonitor(metrics, clockOf(clock));
    }

    /**
     * The fallback dedup transaction policy, used when the idempotency starter is absent.
     *
     * <p>{@code MessagingDedupAutoConfiguration} registers a better-informed one before this, when it can
     * read the claim mode. Here there is nothing to read, so the explicit property is the whole answer -
     * and its default is off, because attaching a transaction manager to a container changes the semantics
     * of every listener on it and a module must not do that on a guess.
     *
     * @param properties this module's configuration
     * @return the policy
     */
    @Bean
    @ConditionalOnMissingBean(DedupTransactionPolicy.class)
    public DedupTransactionPolicy ludwigDedupTransactionPolicy(MessagingProperties properties) {
        Boolean explicit = properties.getDedup().getContainerTransaction();
        return new DedupTransactionPolicy(Boolean.TRUE.equals(explicit),
                explicit == null
                        ? "no idempotency starter on the classpath and no explicit setting"
                        : MessagingProperties.PREFIX + ".dedup.container-transaction=" + explicit);
    }

    /**
     * The one place the platform's consumer decisions are taken.
     *
     * @param kafkaProperties      Boot's Kafka configuration
     * @param objectMapper         the application's mapper, or a plain one when it has none - a module must
     *                             not fail to start a service that never configured Jackson
     * @param properties           this module's configuration
     * @param metrics              the instrumentation
     * @param activityMonitor      the silence monitor, also composed in as a record interceptor
     * @param envelopeReader       reads the envelope for the audit event
     * @param auditSink            where a dead-lettered record is recorded, defaulting to the no-op sink
     *                             that {@code audit-core} ships for a deployment that audits nothing
     * @param clock                the application's clock, if it has exactly one
     * @param recordFilterStrategy the consumer-side dedup filter, when a deployment has one
     * @param recordInterceptors   every interceptor in the context
     * @param transactionManager   for a dedup claim that commits with the listener's work
     * @param meterRegistry        for the Kafka client's own metrics, consumer lag included
     * @param dedupTransaction     whether a dedup container gets the transaction manager
     * @return the builder
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a bean method assembling one collaborator from the context.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    @ConditionalOnMissingBean(ListenerContainerFactoryBuilder.class)
    public ListenerContainerFactoryBuilder ludwigListenerContainerFactoryBuilder(
            KafkaProperties kafkaProperties, ObjectProvider<ObjectMapper> objectMapper,
            MessagingProperties properties, MessagingMetrics metrics,
            ConsumerActivityMonitor activityMonitor, EnvelopeReader envelopeReader,
            ObjectProvider<AuditSink> auditSink, ObjectProvider<Clock> clock,
            ObjectProvider<RecordFilterStrategy<Object, Object>> recordFilterStrategy,
            ObjectProvider<RecordInterceptor<Object, Object>> recordInterceptors,
            ObjectProvider<PlatformTransactionManager> transactionManager,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry,
            DedupTransactionPolicy dedupTransaction) {
        return new ListenerContainerFactoryBuilder(kafkaProperties,
                objectMapper.getIfAvailable(ObjectMapper::new), properties, metrics, activityMonitor,
                envelopeReader, auditSink.getIfAvailable(NoopAuditSink::new), clockOf(clock),
                recordFilterStrategy, recordInterceptors, transactionManager, meterRegistry,
                dedupTransaction.containerTransaction());
    }

    /**
     * The clock this module's timestamps come from.
     *
     * <p>Resolved rather than published, and this module declares no {@code Clock} bean of its own. It used
     * to, guarded by {@code @ConditionalOnMissingBean}, and that was wrong in a way worth recording:
     * {@code rest-client-spring-boot-starter} publishes {@code ludwigRestClientClock}, and whichever
     * autoconfiguration happens to run second backs off - so the two together left the context with two
     * {@code Clock} beans and every unqualified {@code Clock} injection in the application ambiguous.
     * {@code crud-service-example} failed to start for exactly that reason.
     *
     * <p>{@code getIfUnique} rather than {@code getIfAvailable}: with two clocks in the context, neither is
     * this module's to choose, and falling back to the system clock is both correct and the behaviour a
     * deployment that never configured one expects.
     */
    private static Clock clockOf(ObjectProvider<Clock> clock) {
        return clock.getIfUnique(Clock::systemUTC);
    }

    /**
     * Refuses a configuration that cannot mean what it says.
     *
     * @param properties       this module's configuration
     * @param dedupTransaction the resolved dedup transaction policy
     * @return the validator, which runs at startup
     */
    @Bean
    @ConditionalOnMissingBean(MessagingConfigurationValidator.class)
    public MessagingConfigurationValidator ludwigMessagingConfigurationValidator(
            MessagingProperties properties, DedupTransactionPolicy dedupTransaction) {
        return new MessagingConfigurationValidator(properties, dedupTransaction);
    }
}
