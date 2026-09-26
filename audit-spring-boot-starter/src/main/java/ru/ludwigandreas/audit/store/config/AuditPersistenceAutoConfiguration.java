package ru.ludwigandreas.audit.store.config;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.audit.config.AuditCoreAutoConfiguration;
import ru.ludwigandreas.audit.config.AuditProperties;
import ru.ludwigandreas.audit.store.actor.ActorResolverAuditorProvider;
import ru.ludwigandreas.audit.store.repository.AuditEventRepository;
import ru.ludwigandreas.audit.store.metrics.AuditMetrics;
import ru.ludwigandreas.audit.store.sink.JpaAuditSink;
import ru.ludwigandreas.audit.store.web.AuditProblemMapper;
import ru.ludwigandreas.db.core.audit.AuditorProvider;
import ru.ludwigandreas.db.core.repository.BaseRepositoryImpl;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * The {@code audit_event} table, its repository and the sink that writes to it.
 *
 * <p>Separate from {@code audit-core}'s own autoconfiguration so that a module low in the stack gets a
 * working {@code AuditSink} with no database at all: the SLF4J sink alone is a complete configuration, and
 * this class contributes a second sink to the composite when a {@code DataSource} is present.
 *
 * <p>{@code @EntityScan} and {@code @EnableJpaRepositories} scoped to this module's packages, which is how
 * every persistence-carrying starter here does it - a service's own scanning never has to know this table
 * exists.
 *
 * <p><b>It contributes neither a {@code Clock} nor a {@code JPAQueryFactory} bean.</b> Both were tried, both
 * guarded by {@code @ConditionalOnMissingBean}, and that guard does not hold between two autoconfigurations
 * with no ordering: each sees no bean and each registers one, so a consumer injecting the type by name-free
 * autowiring fails to start on an ambiguity neither module caused alone. The {@code Clock} case actually
 * broke {@code crud-service-example}. The read-side fragment builds its own factory over the shared entity
 * manager instead, and the sinks take an {@code ObjectProvider<Clock>}.
 */
@AutoConfiguration
@ConditionalOnClass(EntityManager.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "ludwig.audit.jpa", name = "enabled", matchIfMissing = true)
@AutoConfigureAfter(AuditCoreAutoConfiguration.class)
@EnableConfigurationProperties(AuditProperties.class)
@EntityScan(basePackageClasses = ru.ludwigandreas.audit.store.entity.AuditEventEntity.class)
@EnableJpaRepositories(basePackageClasses = AuditEventRepository.class,
        repositoryBaseClass = BaseRepositoryImpl.class)
public class AuditPersistenceAutoConfiguration {

    /**
     * The table sink.
     *
     * <p>Ordered after the SLF4J sink in the composite, so a failing database write still finds the event
     * already in the log - which is the difference between a lost event and an event somebody can replay
     * from a log line.
     *
     * @param repository  the trail
     * @param clock       supplies each row's write time; an {@code ObjectProvider} rather than a {@code Clock}
     *                    because this module must not add an unqualified {@code Clock} bean to a consumer's
     *                    context, and must not require one either - see {@code AuditCoreAutoConfiguration}
     * @param properties  the configuration
     * @param environment supplies {@code spring.application.name} as the default source system
     * @param metrics     counts rows written, when a metrics stack is present
     * @return the sink
     */
    @Bean
    @Order(100)
    @ConditionalOnMissingBean(JpaAuditSink.class)
    public JpaAuditSink jpaAuditSink(AuditEventRepository repository, ObjectProvider<Clock> clock,
                                     AuditProperties properties, Environment environment,
                                     ObjectProvider<AuditMetrics> metrics) {
        return new JpaAuditSink(repository, clock.getIfAvailable(Clock::systemUTC),
                sourceSystem(properties, environment), metrics.getIfAvailable());
    }

    /**
     * Feeds {@code db-core}'s {@code created_by} / {@code last_modified_by} stamping from the same actor
     * resolution the trail uses.
     *
     * <p>{@code db-core} guards its own provider with {@code @ConditionalOnMissingBean(AuditorProvider)},
     * so publishing this makes the two agree. A service that had published its own provider keeps it, and
     * then owns keeping the two in step - see {@code ActorResolverAuditorProvider}.
     *
     * @param actors the platform's actor resolution
     * @return the bridge
     */
    @Bean
    @ConditionalOnMissingBean(AuditorProvider.class)
    public AuditorProvider<String> auditorProviderFromActorResolver(ActorResolver actors) {
        return new ActorResolverAuditorProvider(actors);
    }

    /**
     * The sink-failure counter, which is how a silently failing sink is noticed.
     *
     * <p>In this module rather than {@code audit-core} because {@code AuditMetrics} also counts rows
     * written and rows purged, which only exist here - and because a library must not drag an
     * observability stack into an application that did not ask for one, so the whole thing is behind
     * {@code @ConditionalOnClass}.
     *
     * <p>Guarded on the {@code MeterRegistry} <em>bean</em> as well as the class, which is not belt and
     * braces: Micrometer is on the classpath of any service using Boot's actuator dependencies, and a
     * service that has the jar without a registry bean - no actuator, a library under test - would
     * otherwise fail to start with an unsatisfied dependency inside the audit wiring. A starter that
     * breaks a context because an optional observability stack is half-present is worse than one with no
     * counters.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(AuditMetrics.class)
        public AuditMetrics auditMetrics(MeterRegistry registry) {
            return new AuditMetrics(registry);
        }
    }

    /**
     * This module's exception, declared as a meaning for the shared problem pipeline to render, and the
     * localized text for it.
     *
     * <p>Only registered when the web-core starter is on the classpath. The mapper and the bundle are in
     * this module rather than {@code audit-core} on purpose: {@code audit-core} must stay free of in-repo
     * dependencies so that {@code web-core} itself can import it for the redaction package, and an
     * optional edge the other way would be a cycle waiting to happen.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ExceptionProblemMapper.class)
    static class ProblemMappingConfiguration {

        @Bean
        @ConditionalOnMissingBean(AuditProblemMapper.class)
        public AuditProblemMapper auditProblemMapper() {
            return new AuditProblemMapper();
        }

        @Bean
        @ConditionalOnMissingBean(name = "auditProblemMessageBundle")
        public ProblemMessageBundle auditProblemMessageBundle() {
            return ProblemMessageBundle.of("i18n/ludwig-audit-messages");
        }
    }

    /**
     * Which deployment is recorded as the writer of each row.
     *
     * <p>Resolved here rather than as a property default because a default that depends on another
     * property cannot be a field initialiser, and a bean that mutated the properties object would depend
     * on bean creation order to be correct.
     */
    private String sourceSystem(AuditProperties properties, Environment environment) {
        String configured = properties.getSourceSystem();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return environment.getProperty("spring.application.name", "unknown");
    }
}
