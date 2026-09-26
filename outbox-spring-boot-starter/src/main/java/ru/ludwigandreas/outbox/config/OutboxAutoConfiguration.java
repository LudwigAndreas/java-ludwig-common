package ru.ludwigandreas.outbox.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import ru.ludwigandreas.db.core.repository.BaseRepositoryImpl;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;
import ru.ludwigandreas.outbox.audit.OutboxAuditLogger;
import ru.ludwigandreas.outbox.audit.PersistingOutboxAuditLogger;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.outbox.audit.AuditSinkOutboxAuditLogger;
import ru.ludwigandreas.job.core.backoff.BackoffCalculator;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcher;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcherRegistry;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.metrics.OutboxMetrics;
import ru.ludwigandreas.outbox.publisher.DefaultOutboxEventPublisher;
import ru.ludwigandreas.outbox.publisher.filter.CompositeOutboxPublishFilter;
import ru.ludwigandreas.outbox.publisher.filter.OutboxPublishFilter;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;
import ru.ludwigandreas.outbox.repository.OutboxStatusHistoryRepository;
import ru.ludwigandreas.outbox.routing.OutboxRouteResolver;
import ru.ludwigandreas.outbox.routing.PropertiesOutboxRouteResolver;
import ru.ludwigandreas.outbox.scheduler.OutboxOutcomeRecorder;
import ru.ludwigandreas.outbox.scheduler.OutboxProcessingService;
import ru.ludwigandreas.outbox.scheduler.OutboxPublisherScheduler;
import ru.ludwigandreas.outbox.scheduler.OutboxStaleReclaimScheduler;
import ru.ludwigandreas.outbox.serialization.JacksonOutboxPayloadSerializer;
import ru.ludwigandreas.outbox.serialization.OutboxPayloadSerializer;

import java.util.List;

@AutoConfiguration
@ConditionalOnClass(EntityManager.class)
@ConditionalOnProperty(prefix = "ludwig.outbox", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(OutboxPayloadSerializer.class)
    public OutboxPayloadSerializer outboxPayloadSerializer(ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(OutboxAutoConfiguration::defaultObjectMapper);
        return new JacksonOutboxPayloadSerializer(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(OutboxRouteResolver.class)
    public OutboxRouteResolver outboxRouteResolver(OutboxProperties properties) {
        return new PropertiesOutboxRouteResolver(properties);
    }

    /**
     * Transitions into {@code outbox_status_history} <em>and</em> into the platform audit trail.
     *
     * <p>{@code outbox_status_history} stays, and it is the one place in this repository where two audit
     * stores is the right answer. It is not only a trail: the dispatcher and its actuator endpoint read it -
     * how many attempts a message has had, when it was last tried, what the last failure said - so
     * collapsing it into {@code audit_event} would couple dispatch to audit retention, and a deployment that
     * shortened its audit retention would silently shorten the dispatcher's own memory. The generic trail
     * gets the same transitions <em>as well</em>, because "who was never told what happened" is an audit
     * question and an auditor should not have to know this module's schema to ask it.
     *
     * @param repository the operational history
     * @param auditSink  the platform trail
     * @return the logger
     */
    @Bean
    @ConditionalOnMissingBean(OutboxAuditLogger.class)
    @ConditionalOnProperty(prefix = "ludwig.outbox.audit", name = "persist-history", havingValue = "true")
    public OutboxAuditLogger persistingOutboxAuditLogger(OutboxStatusHistoryRepository repository,
                                                         AuditSink auditSink) {
        return new PersistingOutboxAuditLogger(repository, new AuditSinkOutboxAuditLogger(auditSink));
    }

    /**
     * Transitions into the platform audit trail alone.
     *
     * <p>Replaces the SLF4J logger this module used to ship. The trail's destination is now a deployment's
     * choice - a log line, the {@code audit_event} table, a SIEM through the outbox itself - rather than
     * this module's.
     *
     * @param auditSink the platform trail
     * @return the logger
     */
    @Bean
    @ConditionalOnMissingBean(OutboxAuditLogger.class)
    public OutboxAuditLogger auditSinkOutboxAuditLogger(AuditSink auditSink) {
        return new AuditSinkOutboxAuditLogger(auditSink);
    }

    @Bean
    @ConditionalOnMissingBean(OutboxEventPublisher.class)
    public OutboxEventPublisher outboxEventPublisher(OutboxMessageRepository repository,
                                                       OutboxPayloadSerializer serializer,
                                                       List<OutboxPublishFilter> filters,
                                                       OutboxRouteResolver routeResolver,
                                                       OutboxProperties properties,
                                                       OutboxAuditLogger auditLogger,
                                                       OutboxMetrics metrics) {
        return new DefaultOutboxEventPublisher(repository, serializer, new CompositeOutboxPublishFilter(filters),
                routeResolver, properties, auditLogger, metrics);
    }

    @Bean
    @ConditionalOnMissingBean(BackoffCalculator.class)
    public BackoffCalculator outboxBackoffCalculator(OutboxProperties properties) {
        return new BackoffCalculator(properties.getRetry().toBackoffPolicy());
    }

    @Bean
    @ConditionalOnMissingBean(OutboxDispatcherRegistry.class)
    public OutboxDispatcherRegistry outboxDispatcherRegistry(List<OutboxDispatcher> dispatchers) {
        return new OutboxDispatcherRegistry(dispatchers);
    }

    @Bean
    @ConditionalOnMissingBean(OutboxOutcomeRecorder.class)
    public OutboxOutcomeRecorder outboxOutcomeRecorder(OutboxMessageRepository repository,
                                                         BackoffCalculator backoffCalculator,
                                                         OutboxAuditLogger auditLogger,
                                                         OutboxMetrics metrics,
                                                         OutboxProperties properties) {
        return new OutboxOutcomeRecorder(repository, backoffCalculator, auditLogger, metrics, properties);
    }

    @Bean
    @ConditionalOnMissingBean(OutboxProcessingService.class)
    public OutboxProcessingService outboxProcessingService(OutboxMessageRepository repository,
                                                             OutboxDispatcherRegistry dispatcherRegistry,
                                                             OutboxOutcomeRecorder outcomeRecorder,
                                                             OutboxMetrics metrics,
                                                             OutboxProperties properties) {
        return new OutboxProcessingService(repository, dispatcherRegistry, outcomeRecorder, metrics, properties);
    }

    @Bean(name = "outboxTaskScheduler")
    @ConditionalOnMissingBean(name = "outboxTaskScheduler")
    public TaskScheduler outboxTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("outbox-scheduler-");
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean(OutboxPublisherScheduler.class)
    @ConditionalOnProperty(prefix = "ludwig.outbox.polling", name = "enabled", matchIfMissing = true)
    public OutboxPublisherScheduler outboxPublisherScheduler(
            OutboxProcessingService processingService,
            @Qualifier("outboxTaskScheduler") TaskScheduler taskScheduler,
            OutboxProperties properties) {
        return new OutboxPublisherScheduler(processingService, taskScheduler,
                properties.getPolling().getInitialDelay(), properties.getPolling().getFixedDelay(),
                properties.getProcessing().getDrainTimeout());
    }

    @Bean
    @ConditionalOnMissingBean(OutboxStaleReclaimScheduler.class)
    @ConditionalOnProperty(prefix = "ludwig.outbox.polling", name = "enabled", matchIfMissing = true)
    public OutboxStaleReclaimScheduler outboxStaleReclaimScheduler(
            OutboxMessageRepository repository,
            @Qualifier("outboxTaskScheduler") TaskScheduler taskScheduler,
            OutboxProperties properties) {
        return new OutboxStaleReclaimScheduler(repository, taskScheduler,
                properties.getProcessing().getStaleTimeout(), properties.getProcessing().getStaleReclaimFixedDelay(),
                properties.getProcessing().getDrainTimeout());
    }

    private static ObjectMapper defaultObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }

    /**
     * Scoped to this module's own packages ({@code basePackageClasses}) so it composes safely alongside
     * the consuming application's own {@code @EnableJpaRepositories}/{@code @EntityScan} declarations,
     * whatever packages those cover - this is what makes the module usable without the consumer having
     * to configure repository/entity scanning for it at all.
     */
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = OutboxMessage.class)
    @EnableJpaRepositories(basePackageClasses = OutboxMessageRepository.class,
            repositoryBaseClass = BaseRepositoryImpl.class)
    static class OutboxJpaConfiguration {
    }
}
