package ru.ludwigandreas.export.config;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.api.ReportWriterFactory;
import ru.ludwigandreas.export.engine.ExecutionPlanner;
import ru.ludwigandreas.export.event.OutboxReportEventPublisher;
import ru.ludwigandreas.export.event.ReportEventPublisher;
import ru.ludwigandreas.export.entity.ExportReportRun;
import ru.ludwigandreas.export.engine.ReportRunEngine;
import ru.ludwigandreas.export.lifecycle.ExportRetentionPurge;
import ru.ludwigandreas.export.lifecycle.ExportRunExecutor;
import ru.ludwigandreas.export.lifecycle.ExportRunPoller;
import ru.ludwigandreas.export.lifecycle.ExportRunService;
import ru.ludwigandreas.export.metrics.ExportMetrics;
import ru.ludwigandreas.export.registry.ReportWriterFactories;
import ru.ludwigandreas.export.repository.ExportReportOutputRepository;
import ru.ludwigandreas.export.repository.ExportReportRunRepository;
import ru.ludwigandreas.job.core.claim.JobInstanceIdentity;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;
import ru.ludwigandreas.job.core.lock.RunLock;

/**
 * Wires the half of the module that touches a database.
 *
 * <p>Separate from {@link ExportAutoConfiguration} because the two halves have genuinely different
 * requirements. The engine needs a definition, a writer and somewhere to put a file; the run
 * lifecycle needs a datasource, a transaction manager, a scheduler and {@code job-core}'s identity
 * and lock. A service that only produces reports synchronously - a small admin tool, a batch job -
 * gets the first half and none of this, and a service that has no {@link ExecutionPlanner} because
 * it drives the engine itself gets neither the poller nor a startup failure about it.
 *
 * <p>The entity and repository scanning is scoped to this module's own packages, in a nested
 * configuration class rather than on this one. Scanning wider from a library is how a starter comes
 * to own the consuming application's entity discovery and then breaks it by adding a package; and
 * {@code repositoryBaseClass} has to be named, because {@code db-core}'s {@code BaseRepository}
 * declares methods - {@code getByIdOrThrow} and friends - that only its own implementation provides.
 * Without it Spring Data tries to derive a query from the method name and fails at context start
 * with a message about a property called "throw".
 */
@AutoConfiguration
@ConditionalOnClass(name = "jakarta.persistence.EntityManager")
@ConditionalOnProperty(prefix = "ludwig.export", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(ExportProperties.class)
public class ExportPersistenceAutoConfiguration {

    /** Bean name of the pool a claimed run executes on. */
    public static final String RUN_EXECUTOR = "exportRunExecutorPool";

    /** Bean name of the scheduler that renews a running run's lease. */
    public static final String HEARTBEAT_SCHEDULER = "exportHeartbeatScheduler";

    /** Every registered format, by id, shared by the planner and the executor. */
    @Bean
    @ConditionalOnMissingBean
    public ReportWriterFactories reportWriterFactories(ObjectProvider<ReportWriterFactory> factories) {
        return new ReportWriterFactories(factories.orderedStream().toList());
    }

    /** Every write to a run row. */
    @Bean
    @ConditionalOnMissingBean
    public ExportRunService exportRunService(ExportReportRunRepository runs,
                                             ExportReportOutputRepository outputs,
                                             ExportProperties properties,
                                             ObjectProvider<Clock> clock,
                                             ReportEventPublisher events) {
        return new ExportRunService(runs, outputs, properties, clock.getIfAvailable(Clock::systemUTC),
                events);
    }

    /**
     * The fallback announcer: nothing at all.
     *
     * <p>Registered only when the nested outbox configuration did not produce one. Spring processes
     * a configuration class's member classes before its own {@code @Bean} methods, which is what
     * makes {@code @ConditionalOnMissingBean} here see the outbox publisher if there is one.
     */
    @Bean
    @ConditionalOnMissingBean
    public ReportEventPublisher reportEventPublisher() {
        return ReportEventPublisher.NONE;
    }

    /**
     * Where a claimed run executes.
     *
     * <p>Exactly {@code poller.concurrency} threads and a queue of zero: the poller claims against a
     * semaphore of the same size, so a submission that could not start immediately would mean the
     * two counts had drifted apart, and a queue would hide that by accepting work the semaphore says
     * there is no room for.
     */
    @Bean(name = RUN_EXECUTOR, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = RUN_EXECUTOR)
    public ExecutorService exportRunExecutorPool(ExportProperties properties) {
        return Executors.newFixedThreadPool(properties.getPoller().getConcurrency(),
                namedThreads("ludwig-export-run-"));
    }

    /**
     * Where lease renewals run.
     *
     * <p>Its own scheduler, not the application's {@code TaskScheduler}: a heartbeat that queued
     * behind somebody else's scheduled job would let a lease expire while the run holding it is alive
     * and well, and the run would then be executed twice. One thread is enough for a renewal that
     * runs once a minute per active run.
     */
    @Bean(name = HEARTBEAT_SCHEDULER, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = HEARTBEAT_SCHEDULER)
    public ScheduledExecutorService exportHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(namedThreads("ludwig-export-heartbeat-"));
    }

    /** The scheduler the poller and the purge register themselves with. */
    @Bean(name = "exportTaskScheduler", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "exportTaskScheduler")
    public TaskScheduler exportTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ludwig-export-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        return scheduler;
    }

    // SUPPRESS CHECKSTYLE ParameterNumber - a @Bean method whose arguments are all named beans.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ExecutionPlanner.class)
    public ExportRunExecutor exportRunExecutor(ExecutionPlanner planner, ReportRunEngine engine,
                                               ExportRunService lifecycle,
                                               ExportReportRunRepository runs, AuditSink audit,
                                               ExportMetrics metrics, ExportProperties properties,
                                               JobInstanceIdentity identity,
                                               ObjectProvider<Clock> clock,
                                               ScheduledExecutorService heartbeats,
                                               ReportWriterFactories formats) {
        return new ExportRunExecutor(planner, engine, lifecycle, runs, audit, metrics, properties,
                identity, clock.getIfAvailable(Clock::systemUTC), heartbeats, formats);
    }

    /**
     * The poller, when this instance is configured to execute deferred runs.
     *
     * <p>Switchable per instance rather than per estate: a deployment that separates the tier taking
     * requests from the tier producing files turns it off on the first and on on the second, and
     * nothing else changes - the claim is the only coordination between them.
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a @Bean method whose arguments are all named beans.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ExportRunExecutor.class)
    @ConditionalOnProperty(prefix = "ludwig.export.poller", name = "enabled", matchIfMissing = true)
    public ExportRunPoller exportRunPoller(TaskScheduler exportTaskScheduler,
                                           ExportReportRunRepository runs, ExportRunExecutor executor,
                                           ExportProperties properties, JobInstanceIdentity identity,
                                           ObjectProvider<Clock> clock, ExecutorService workers) {
        return new ExportRunPoller(exportTaskScheduler, runs, executor, properties, identity,
                clock.getIfAvailable(Clock::systemUTC), workers);
    }

    /** The retention purge, under {@code job-core}'s leased lock so one instance runs it at a time. */
    // SUPPRESS CHECKSTYLE ParameterNumber - a @Bean method whose arguments are all named beans.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RunLock.class)
    public ExportRetentionPurge exportRetentionPurge(TaskScheduler exportTaskScheduler,
                                                     ExportReportOutputRepository outputs,
                                                     ReportSink sink, RunLock lock,
                                                     ExportProperties properties,
                                                     ExportMetrics metrics,
                                                     ObjectProvider<Clock> clock) {
        return new ExportRetentionPurge(exportTaskScheduler, outputs, sink, lock, properties, metrics,
                clock.getIfAvailable(Clock::systemUTC));
    }

    /**
     * Announces finished reports through the transactional outbox, when there is one.
     *
     * <p>A nested class guarded by {@code @ConditionalOnClass} so that a service without
     * {@code outbox-spring-boot-starter} never loads a type it does not have. Conditional on the
     * <em>bean</em> as well as the class, because the starter's own autoconfiguration can be
     * switched off, and a publisher that existed on the classpath but not in the context would fail
     * the injection rather than fall back.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(OutboxEventPublisher.class)
    @ConditionalOnBean(OutboxEventPublisher.class)
    static class OutboxReportEventConfiguration {

        @Bean
        @ConditionalOnMissingBean(ReportEventPublisher.class)
        ReportEventPublisher outboxReportEventPublisher(OutboxEventPublisher outbox) {
            return new OutboxReportEventPublisher(outbox);
        }
    }

    /**
     * Scoped to this module's own packages, so it composes with whatever the consuming application
     * scans for itself. See the class comment for why {@code repositoryBaseClass} is named.
     */
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = ExportReportRun.class)
    @EnableJpaRepositories(basePackageClasses = ExportReportRunRepository.class,
            repositoryBaseClass = BaseRepositoryImpl.class)
    static class ExportJpaConfiguration {
    }

    /**
     * Named, non-daemon threads.
     *
     * <p>Non-daemon because a run in flight holds a lease and a temp file; letting the JVM exit out
     * from under it would leave both for the reclaim to sort out. Named because this module's
     * problems are usually diagnosed from a thread dump.
     */
    private ThreadFactory namedThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
