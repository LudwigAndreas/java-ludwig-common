package ru.ludwigandreas.export.config;

import jakarta.validation.Validator;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.export.api.ReportWriterFactory;
import ru.ludwigandreas.export.engine.DefaultExecutionPlanner;
import ru.ludwigandreas.export.engine.ExecutionPlanner;
import ru.ludwigandreas.export.engine.ReportFilterParser;
import ru.ludwigandreas.export.engine.ReportParameterBinder;
import ru.ludwigandreas.export.engine.ReportScopeResolver;
import ru.ludwigandreas.export.engine.ReportRunEngine;
import ru.ludwigandreas.export.engine.SpringReportParameterBinder;
import ru.ludwigandreas.export.filter.ReportNotFilterableException;
import ru.ludwigandreas.export.i18n.ExportMessages;
import ru.ludwigandreas.export.lifecycle.ExportRunService;
import ru.ludwigandreas.export.lifecycle.ExportSubscriptionScheduler;
import ru.ludwigandreas.export.lifecycle.ReportRequestService;
import ru.ludwigandreas.export.lifecycle.SavedReportService;
import ru.ludwigandreas.export.registry.ReportDefinitionRegistry;
import ru.ludwigandreas.export.registry.ReportWriterFactories;
import ru.ludwigandreas.export.repository.ExportReportSubscriptionRepository;
import ru.ludwigandreas.export.repository.ExportSavedReportRepository;
import ru.ludwigandreas.export.security.ReportAuthorities;
import ru.ludwigandreas.job.core.lock.RunLock;

/**
 * Wires the planner, the security integration, the REST layer and the subscription scheduler.
 *
 * <h2>Every integration is conditional on its own starter</h2>
 *
 * <p>{@code security}, {@code odata-filter}, {@code outbox} and the servlet stack are all optional
 * dependencies, and each one's integration appears only when the thing it integrates with is
 * actually present. The defaults left behind are deliberately the strict ones: no scope resolver
 * means no predicate, and no filter parser means a filter is refused rather than dropped.
 *
 * <p>That asymmetry is on purpose. A missing filter parser produces an error a caller sees
 * immediately; a missing scope resolver produces a report with no row-level restriction, and there
 * is no way for this module to tell that apart from a report that genuinely has none. So the scope
 * default is documented as a claim somebody has to make rather than something to detect - see
 * {@code ReportScopeResolver} - and the security-backed resolver takes over the moment the starter
 * is on the classpath.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ludwig.export", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(ExportProperties.class)
public class ExportWebAutoConfiguration {

    /**
     * Binds request parameters to the definition's typed record.
     *
     * <p>Takes the application's own {@code Validator} so that a report parameter's constraint
     * message is resolved by the same message source, in the same locale, as every other violation
     * the service returns.
     */
    @Bean
    @ConditionalOnMissingBean
    public ReportParameterBinder reportParameterBinder(ObjectProvider<Validator> validator) {
        return new SpringReportParameterBinder(validator.getIfAvailable());
    }

    /**
     * The planner: the one place a report's scope, columns and formats are decided.
     *
     * <p>Conditional on the registry, so a service that has not wired the module's core half does not
     * get a planner that would fail on its first use.
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a @Bean method whose arguments are all named beans.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReportDefinitionRegistry.class)
    public ExecutionPlanner executionPlanner(ReportDefinitionRegistry registry,
                                             ObjectProvider<ReportWriterFactory> factories,
                                             ReportParameterBinder parameters,
                                             ObjectProvider<ReportScopeResolver> scopes,
                                             ObjectProvider<ReportFilterParser> filters,
                                             ExportMessages messages, ExportProperties properties) {
        return new DefaultExecutionPlanner(registry, factories.orderedStream().toList(), parameters,
                scopes.getIfAvailable(() -> ReportScopeResolver.UNRESTRICTED),
                filters.getIfAvailable(() -> refuseFilters()), messages, properties);
    }

    /**
     * The default filter parser: refuse.
     *
     * <p>A method rather than a lambda in the call above so the reason has somewhere to live. A
     * filter that was silently dropped would produce a file covering far more data than was asked
     * for, which for a report is a larger failure than an error rather than a smaller one.
     */
    private static ReportFilterParser refuseFilters() {
        return (definition, filter) -> {
            throw new ReportNotFilterableException(definition.getKey());
        };
    }

    /** Accepts requests and decides whether the caller waits. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({ExecutionPlanner.class, ExportRunService.class})
    public ReportRequestService reportRequestService(ReportDefinitionRegistry registry,
                                                     ExecutionPlanner planner, ReportRunEngine engine,
                                                     ExportRunService lifecycle,
                                                     ReportAuthorities authorities,
                                                     ExportProperties properties) {
        return new ReportRequestService(registry, planner, engine, lifecycle, authorities, properties);
    }

    /** Saved configurations, validated on write and on load. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ExportSavedReportRepository.class)
    public SavedReportService savedReportService(ExportSavedReportRepository savedReports,
                                                 ExportReportSubscriptionRepository subscriptions,
                                                 ReportDefinitionRegistry registry,
                                                 ReportWriterFactories formats) {
        return new SavedReportService(savedReports, subscriptions, registry, formats);
    }

    /** Turns due subscriptions into runs, one instance at a time. */
    // SUPPRESS CHECKSTYLE ParameterNumber - a @Bean method whose arguments are all named beans.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SavedReportService.class, ReportRequestService.class, RunLock.class})
    public ExportSubscriptionScheduler exportSubscriptionScheduler(
            TaskScheduler exportTaskScheduler, ExportReportSubscriptionRepository subscriptions,
            SavedReportService savedReports, ReportRequestService requests,
            ReportWriterFactories formats, RunLock lock, ExportProperties properties,
            ObjectProvider<Clock> clock) {
        return new ExportSubscriptionScheduler(exportTaskScheduler, subscriptions, savedReports,
                requests, formats, lock, properties, clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * The fallback authority source: nothing granted.
     *
     * <p>Registered only when the security integration did not produce one. It is the strict default:
     * a requester with no authorities sees no restricted column and can run no report that requires
     * one, which fails closed in both directions.
     */
    @Bean
    @ConditionalOnMissingBean
    public ReportAuthorities reportAuthorities() {
        return ReportAuthorities.NONE;
    }
}
