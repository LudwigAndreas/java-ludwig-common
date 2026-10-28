package ru.ludwigandreas.reconciliation.config;

import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.reconciliation.api.EnumerationTask;
import ru.ludwigandreas.reconciliation.engine.CorrelationIdSource;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationOperations;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRegistry;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRunner;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRuntime;
import ru.ludwigandreas.reconciliation.engine.enumeration.MemberPageApplier;
import ru.ludwigandreas.reconciliation.engine.enumeration.RegisteredEnumeration;
import ru.ludwigandreas.reconciliation.engine.enumeration.ScopeDiscoveryService;
import ru.ludwigandreas.reconciliation.engine.enumeration.ScopeStateService;
import ru.ludwigandreas.reconciliation.engine.enumeration.ScopeWalker;
import ru.ludwigandreas.reconciliation.engine.enumeration.SweepService;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.payload.PayloadCodec;
import ru.ludwigandreas.reconciliation.quota.RateLimitRegistry;
import ru.ludwigandreas.reconciliation.repository.SyncScopeMemberRepository;
import ru.ludwigandreas.reconciliation.repository.SyncScopeStateRepository;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Wires the second task kind: enumerations.
 *
 * <p>A configuration of its own, after {@link ReconciliationAutoConfiguration}, rather than more
 * methods on that class. Everything an enumeration shares with a key-addressed task - the run lease,
 * the scheduler, the fetch executor, the rate limits, the payload codec, the metrics and the audit
 * sink - is taken from there as a bean; nothing is declared twice.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}, so a service that publishes its own steps this
 * module's aside. A service with no enumeration gets an empty registry: nothing is scheduled and
 * nothing is written to the two tables.
 */
@AutoConfiguration
@ConditionalOnClass(EntityManager.class)
@ConditionalOnProperty(prefix = "ludwig.reconciliation", name = "enabled", matchIfMissing = true)
@AutoConfigureAfter(ReconciliationAutoConfiguration.class)
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationEnumerationAutoConfiguration {

    /** The resolved settings of every configured enumeration, as one bean. */
    @Bean
    @ConditionalOnMissingBean(EnumerationSettingsCatalogue.class)
    public EnumerationSettingsCatalogue reconciliationEnumerationSettings(ReconciliationProperties properties) {
        return new EnumerationSettingsCatalogue(EnumerationSettingsResolver.resolveAll(properties));
    }

    /**
     * The startup validator. The beans are taken through an {@link ObjectProvider} because a list
     * injection point with no candidates fails the context, and a service with no enumeration is the
     * common case rather than a mistake.
     */
    @Bean
    @ConditionalOnMissingBean(EnumerationConfigurationValidator.class)
    public EnumerationConfigurationValidator reconciliationEnumerationConfigurationValidator(
            ReconciliationProperties properties,
            EnumerationSettingsCatalogue settings,
            ObjectProvider<EnumerationTask<?, ?, ?, ?>> taskBeans,
            RestClientPresence restClients) {
        return new EnumerationConfigurationValidator(properties, settings.byName(),
                taskBeans.orderedStream().toList(), restClients::isConfigured);
    }

    @Bean
    @ConditionalOnMissingBean(EnumerationRegistry.class)
    @DependsOn("reconciliationEnumerationConfigurationValidator")
    public EnumerationRegistry reconciliationEnumerationRegistry(
            ObjectProvider<EnumerationTask<?, ?, ?, ?>> taskBeans,
            EnumerationSettingsCatalogue settings) {
        List<RegisteredEnumeration<?, ?, ?, ?>> registered = taskBeans.orderedStream()
                .filter(bean -> settings.byName().containsKey(bean.name()))
                .<RegisteredEnumeration<?, ?, ?, ?>>map(bean -> register(bean, settings.byName().get(bean.name())))
                .toList();
        return new EnumerationRegistry(registered);
    }

    private static <S, SK, M, MK> RegisteredEnumeration<S, SK, M, MK> register(
            EnumerationTask<S, SK, M, MK> bean, EnumerationSettings settings) {
        return new RegisteredEnumeration<>(bean, settings);
    }

    @Bean
    @ConditionalOnMissingBean(ScopeStateService.class)
    public ScopeStateService reconciliationScopeStateService(SyncScopeStateRepository states,
                                                             SyncScopeMemberRepository members,
                                                             PlatformTransactionManager transactionManager) {
        return new ScopeStateService(states, members, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean(ScopeDiscoveryService.class)
    public ScopeDiscoveryService reconciliationScopeDiscoveryService(ScopeStateService states) {
        return new ScopeDiscoveryService(states);
    }

    @Bean
    @ConditionalOnMissingBean(MemberPageApplier.class)
    public MemberPageApplier reconciliationMemberPageApplier(SyncScopeMemberRepository members,
                                                             ScopeStateService states,
                                                             PayloadCodec payloadCodec,
                                                             ReconciliationMetrics metrics,
                                                             AuditSink auditSink,
                                                             PlatformTransactionManager transactionManager) {
        return new MemberPageApplier(members, states, payloadCodec, metrics, auditSink, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean(SweepService.class)
    public SweepService reconciliationSweepService(SyncScopeMemberRepository members,
                                                   ScopeStateService states,
                                                   MemberPageApplier applier,
                                                   AuditSink auditSink,
                                                   PlatformTransactionManager transactionManager) {
        return new SweepService(members, states, applier, auditSink, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean(ScopeWalker.class)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ScopeWalker reconciliationScopeWalker(ScopeStateService states,
                                                 MemberPageApplier applier,
                                                 SweepService sweeps,
                                                 SyncScopeMemberRepository members,
                                                 PayloadCodec payloadCodec,
                                                 RateLimitRegistry rateLimits,
                                                 ReconciliationMetrics metrics,
                                                 AuditSink auditSink) {
        return new ScopeWalker(states, applier, sweeps, members, payloadCodec, rateLimits, metrics, auditSink);
    }

    @Bean
    @ConditionalOnMissingBean(EnumerationRunner.class)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public EnumerationRunner reconciliationEnumerationRunner(
            RunLock runLock,
            ScopeDiscoveryService discovery,
            ScopeStateService states,
            ScopeWalker walker,
            SweepService sweeps,
            SyncScopeMemberRepository members,
            @Qualifier("reconciliationFetchExecutor") ExecutorService executor,
            ReconciliationMetrics metrics,
            AuditSink auditSink,
            CorrelationIdSource correlationIds) {
        return new EnumerationRunner(runLock, discovery, states, walker, sweeps, members, executor,
                metrics, auditSink, correlationIds);
    }

    @Bean
    @ConditionalOnMissingBean(EnumerationRuntime.class)
    public EnumerationRuntime reconciliationEnumerationRuntime(
            EnumerationRegistry registry,
            EnumerationRunner runner,
            SyncScopeStateRepository states,
            SyncScopeMemberRepository members,
            ReconciliationMetrics metrics,
            @Qualifier("reconciliationTaskScheduler") TaskScheduler scheduler) {
        return new EnumerationRuntime(registry, runner, states, members, metrics, scheduler);
    }

    /** What the administrative endpoints act through for enumerations. */
    @Bean
    @ConditionalOnMissingBean(EnumerationOperations.class)
    public EnumerationOperations reconciliationEnumerationOperations(EnumerationRegistry registry,
                                                                     EnumerationRunner runner,
                                                                     EnumerationRuntime runtime,
                                                                     SyncScopeStateRepository states,
                                                                     SyncScopeMemberRepository members) {
        return new EnumerationOperations(registry, runner, runtime, states, members);
    }
}
