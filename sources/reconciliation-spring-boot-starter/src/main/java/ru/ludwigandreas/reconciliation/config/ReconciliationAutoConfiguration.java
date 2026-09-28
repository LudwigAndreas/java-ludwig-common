package ru.ludwigandreas.reconciliation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
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
import org.springframework.transaction.PlatformTransactionManager;
import ru.ludwigandreas.db.core.repository.BaseRepositoryImpl;
import ru.ludwigandreas.job.core.claim.ClaimOwner;
import ru.ludwigandreas.job.core.claim.JobInstanceIdentity;
import ru.ludwigandreas.job.core.config.JobCoreAutoConfiguration;
import ru.ludwigandreas.job.core.lock.JdbcRunLock;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.reconciliation.api.SyncTask;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.reconciliation.engine.ApplyService;
import ru.ludwigandreas.reconciliation.engine.CorrelationIdSource;
import ru.ludwigandreas.reconciliation.engine.DirectApplyService;
import ru.ludwigandreas.reconciliation.engine.FetchWalker;
import ru.ludwigandreas.reconciliation.engine.ReconciliationRuntime;
import ru.ludwigandreas.reconciliation.engine.RecordApplier;
import ru.ludwigandreas.reconciliation.engine.RegisteredTask;
import ru.ludwigandreas.reconciliation.engine.StagingService;
import ru.ludwigandreas.reconciliation.engine.TaskRegistry;
import ru.ludwigandreas.reconciliation.engine.TaskRunner;
import ru.ludwigandreas.reconciliation.engine.TaskStateService;
import ru.ludwigandreas.reconciliation.entity.SyncInboxRecord;
import ru.ludwigandreas.reconciliation.job.DemandKeys;
import ru.ludwigandreas.reconciliation.job.RemoteJobCollectService;
import ru.ludwigandreas.reconciliation.job.RemoteJobMaintenanceService;
import ru.ludwigandreas.reconciliation.job.RemoteJobPollService;
import ru.ludwigandreas.reconciliation.job.RemoteJobSettlement;
import ru.ludwigandreas.reconciliation.job.RemoteJobSubmitService;
import ru.ludwigandreas.reconciliation.metrics.ReconciliationMetrics;
import ru.ludwigandreas.reconciliation.payload.PayloadCodec;
import ru.ludwigandreas.reconciliation.quota.DatabaseQuota;
import ru.ludwigandreas.reconciliation.quota.QuotaReclaimService;
import ru.ludwigandreas.reconciliation.quota.QuotaRegistry;
import ru.ludwigandreas.reconciliation.quota.RateLimitRegistry;
import ru.ludwigandreas.reconciliation.repository.QuotaLeaseRepository;
import ru.ludwigandreas.reconciliation.repository.QuotaWaiterRepository;
import ru.ludwigandreas.reconciliation.repository.SyncInboxRecordRepository;
import ru.ludwigandreas.reconciliation.repository.SyncRemoteJobRepository;
import ru.ludwigandreas.reconciliation.repository.SyncTaskStateRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Wires the module.
 *
 * <p>Almost every bean here is guarded by {@code @ConditionalOnMissingBean}, and that is the module's
 * extension model: a service that wants a different metrics sink, audit logger, payload codec or
 * quota publishes its own bean and this configuration steps aside. Adding an integration never
 * requires editing this starter.
 */
@AutoConfiguration
@ConditionalOnClass(EntityManager.class)
@ConditionalOnProperty(prefix = "ludwig.reconciliation", name = "enabled", matchIfMissing = true)
@AutoConfigureAfter(JobCoreAutoConfiguration.class)
@EnableConfigurationProperties(ReconciliationProperties.class)
public class ReconciliationAutoConfiguration {

    /**
     * How long an idle fetch thread is kept before it is retired.
     *
     * <p>The pool is unbounded in size and bounded by the per-task permit counts instead, so the
     * threads that exist track what the tasks are actually allowed to do. Keeping them a minute means
     * a task on a thirty-second cadence reuses its threads while a task on a four-hour one costs
     * nothing between sweeps.
     */
    private static final long FETCH_THREAD_KEEP_ALIVE_SECONDS = 60L;

    /** Scheduler threads. Every pass is short and delegates its work, so a small pool is ample. */
    private static final int SCHEDULER_POOL_SIZE = 4;

    /**
     * The object mapper used for staged payloads.
     *
     * @param objectMapperProvider the application's mapper, when it has one
     * @return the payload codec
     */
    @Bean
    @ConditionalOnMissingBean(PayloadCodec.class)
    public PayloadCodec reconciliationPayloadCodec(ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new PayloadCodec(objectMapperProvider.getIfAvailable(
                ReconciliationAutoConfiguration::defaultObjectMapper));
    }

    /**
     * The demand-keys codec for the asynchronous-job shape.
     *
     * @param objectMapperProvider the application's mapper, when it has one
     * @return the codec
     */
    @Bean
    @ConditionalOnMissingBean(DemandKeys.class)
    public DemandKeys reconciliationDemandKeys(ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new DemandKeys(objectMapperProvider.getIfAvailable(
                ReconciliationAutoConfiguration::defaultObjectMapper));
    }

    /**
     * This instance's identity. Falls back to deriving one when {@code job-core}'s bean is absent,
     * which happens only if a consumer has switched that module's autoconfiguration off.
     *
     * @param identityProvider {@code job-core}'s identity, when present
     * @param properties       this module's configuration
     * @return the identity string written into every owner column
     */
    @Bean(name = "reconciliationInstanceOwner")
    @ConditionalOnMissingBean(name = "reconciliationInstanceOwner")
    public String reconciliationInstanceOwner(ObjectProvider<JobInstanceIdentity> identityProvider,
                                              ReconciliationProperties properties) {
        if (properties.getInstance() != null && !properties.getInstance().isBlank()) {
            return properties.getInstance();
        }
        return identityProvider.getIfAvailable(
                () -> new JobInstanceIdentity(ClaimOwner.resolve(null))).owner();
    }

    /**
     * The run lock. Falls back to building one over the data source when {@code job-core}'s bean is
     * absent, for the same reason as the identity above.
     *
     * @param runLockProvider {@code job-core}'s lock, when present
     * @param dataSource      the application's data source
     * @param owner           this instance's identity
     * @return the run lock
     */
    @Bean
    @ConditionalOnMissingBean(RunLock.class)
    public RunLock reconciliationRunLock(ObjectProvider<RunLock> runLockProvider,
                                         DataSource dataSource,
                                         @Qualifier("reconciliationInstanceOwner") String owner) {
        return runLockProvider.getIfAvailable(() -> new JdbcRunLock(dataSource, owner));
    }

    /**
     * Correlation for a run, when the observability starter is absent.
     *
     * <p>{@code ReconciliationObservabilityAutoConfiguration} supplies the propagating version when
     * that starter is present; it is a separate configuration class precisely so that the reference to
     * its types exists only where a {@code @ConditionalOnClass} has established they are there.
     *
     * @return the standalone correlation source
     */
    @Bean
    @ConditionalOnMissingBean(CorrelationIdSource.class)
    public CorrelationIdSource reconciliationCorrelationIdSource() {
        return CorrelationIdSource.standalone();
    }

    /**
     * The validator's "is this REST client configured?" check, when the rest-client starter is absent.
     *
     * <p>Answers {@code false} for everything, which is the correct answer: a task naming a REST
     * client in a service that has none is a configuration error worth failing on, not one to wave
     * through.
     *
     * @return the presence check
     */
    @Bean
    @ConditionalOnMissingBean(RestClientPresence.class)
    public RestClientPresence reconciliationRestClientPresence() {
        return name -> false;
    }

    /**
     * The shared fetch executor.
     *
     * <p>One pool for every task, with each task's {@code max-concurrency} enforced by a permit count
     * rather than by a pool of its own. A pool per task means a service with eight integrations
     * carries eight pools sized for their individual peaks, all idle most of the time.
     *
     * @return the executor
     */
    @Bean(name = "reconciliationFetchExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "reconciliationFetchExecutor")
    public ExecutorService reconciliationFetchExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE,
                FETCH_THREAD_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, new SynchronousQueue<>(),
                runnable -> {
                    Thread thread = Executors.defaultThreadFactory().newThread(runnable);
                    thread.setName("reconciliation-fetch-" + thread.getId());
                    thread.setDaemon(true);
                    return thread;
                });
        return executor;
    }

    /**
     * The scheduler every pass registers itself with.
     *
     * @return the scheduler
     */
    @Bean(name = "reconciliationTaskScheduler")
    @ConditionalOnMissingBean(name = "reconciliationTaskScheduler")
    public TaskScheduler reconciliationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(SCHEDULER_POOL_SIZE);
        scheduler.setThreadNamePrefix("reconciliation-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }

    /**
     * The merged per-task settings.
     *
     * @param properties the bound configuration
     * @return settings by task name
     */
    @Bean
    public Map<String, TaskSettings> reconciliationTaskSettings(ReconciliationProperties properties) {
        return TaskSettingsResolver.resolveAll(properties);
    }

    /**
     * Refuses to start on a configuration that is individually valid and jointly wrong.
     *
     * @param properties  the bound configuration
     * @param settings    the merged per-task settings
     * @param taskBeans   every discovered task bean
     * @param restClients whether a named REST client is configured
     * @return the validator
     */
    @Bean
    @ConditionalOnMissingBean(ReconciliationConfigurationValidator.class)
    public ReconciliationConfigurationValidator reconciliationConfigurationValidator(
            ReconciliationProperties properties,
            Map<String, TaskSettings> settings,
            List<SyncTask<?, ?, ?>> taskBeans,
            RestClientPresence restClients) {
        Predicate<String> exists = restClients::isConfigured;
        return new ReconciliationConfigurationValidator(properties, settings, taskBeans, exists);
    }

    /**
     * The discovered tasks, paired with their settings.
     *
     * <p>Depends on the validator by name so that a context with a bad configuration fails in the
     * validator's terms rather than somewhere downstream with a null.
     *
     * @param taskBeans every discovered task bean
     * @param settings  the merged per-task settings
     * @return the registry
     */
    @Bean
    @ConditionalOnMissingBean(TaskRegistry.class)
    @org.springframework.context.annotation.DependsOn("reconciliationConfigurationValidator")
    public TaskRegistry reconciliationTaskRegistry(List<SyncTask<?, ?, ?>> taskBeans,
                                                   Map<String, TaskSettings> settings) {
        List<RegisteredTask<?, ?, ?>> registered = taskBeans.stream()
                .filter(bean -> settings.containsKey(bean.name()))
                .<RegisteredTask<?, ?, ?>>map(bean -> register(bean, settings.get(bean.name())))
                .toList();
        return new TaskRegistry(registered);
    }

    /** Captures the bean's wildcards so the pairing is typed. */
    private static <I, K, O> RegisteredTask<I, K, O> register(SyncTask<I, K, O> bean, TaskSettings settings) {
        return new RegisteredTask<>(bean, settings);
    }

    /**
     * Partner-scoped request-rate budgets.
     *
     * @param properties the bound configuration
     * @return the registry
     */
    @Bean
    @ConditionalOnMissingBean(RateLimitRegistry.class)
    public RateLimitRegistry reconciliationRateLimits(ReconciliationProperties properties) {
        return new RateLimitRegistry(properties.getRateLimits());
    }

    /**
     * The database-backed quota.
     *
     * @param properties         the bound configuration
     * @param leases             the lease table
     * @param waiters            the queue table
     * @param entityManager      used for the advisory lock that serializes acquisitions
     * @param metrics            instrumentation
     * @param auditLogger        the audit trail
     * @param owner              this instance's identity
     * @param transactionManager the transaction manager
     * @return the quota
     */
    @Bean
    @ConditionalOnMissingBean(DatabaseQuota.class)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public DatabaseQuota reconciliationQuota(ReconciliationProperties properties,
                                             QuotaLeaseRepository leases,
                                             QuotaWaiterRepository waiters,
                                             EntityManager entityManager,
                                             ReconciliationMetrics metrics,
                                             AuditSink auditLogger,
                                             @Qualifier("reconciliationInstanceOwner") String owner,
                                             PlatformTransactionManager transactionManager) {
        return new DatabaseQuota(properties.getQuotas(), leases, waiters, entityManager,
                metrics, auditLogger, owner, transactionManager);
    }

    /**
     * The per-task view of the quotas.
     *
     * @param quota the database-backed quota
     * @return the registry
     */
    @Bean
    @ConditionalOnMissingBean(QuotaRegistry.class)
    public QuotaRegistry reconciliationQuotaRegistry(DatabaseQuota quota) {
        return new QuotaRegistry(quota);
    }

    /**
     * Writes fetch outcomes down.
     *
     * @param repository   the staging table
     * @param payloadCodec serialization and hashing
     * @param metrics      instrumentation
     * @param auditLogger  the audit trail
     * @return the staging service
     */
    @Bean
    @ConditionalOnMissingBean(StagingService.class)
    public StagingService reconciliationStagingService(SyncInboxRecordRepository repository,
                                                       PayloadCodec payloadCodec,
                                                       ReconciliationMetrics metrics,
                                                       AuditSink auditLogger) {
        return new StagingService(repository, payloadCodec, metrics, auditLogger);
    }

    /**
     * Applies one staged record, in its own transaction.
     *
     * @param repository   the staging table
     * @param payloadCodec payload deserialization
     * @param metrics      instrumentation
     * @param auditLogger  the audit trail
     * @return the applier
     */
    @Bean
    @ConditionalOnMissingBean(RecordApplier.class)
    public RecordApplier reconciliationRecordApplier(SyncInboxRecordRepository repository,
                                                     PayloadCodec payloadCodec,
                                                     ReconciliationMetrics metrics,
                                                     AuditSink auditLogger) {
        return new RecordApplier(repository, payloadCodec, metrics, auditLogger);
    }

    /**
     * The apply pass.
     *
     * @param repository the staging table
     * @param applier    applies one record
     * @param metrics    instrumentation
     * @param owner      this instance's identity
     * @return the apply service
     */
    @Bean
    @ConditionalOnMissingBean(ApplyService.class)
    public ApplyService reconciliationApplyService(SyncInboxRecordRepository repository,
                                                   RecordApplier applier,
                                                   ReconciliationMetrics metrics,
                                                   @Qualifier("reconciliationInstanceOwner") String owner) {
        return new ApplyService(repository, applier, metrics, owner);
    }

    /**
     * Applies fetch outcomes inline, under {@code mode: direct}.
     *
     * @param metrics            instrumentation
     * @param auditLogger        the audit trail
     * @param transactionManager the transaction manager
     * @return the direct apply service
     */
    @Bean
    @ConditionalOnMissingBean(DirectApplyService.class)
    public DirectApplyService reconciliationDirectApplyService(ReconciliationMetrics metrics,
                                                               AuditSink auditLogger,
                                                               PlatformTransactionManager transactionManager) {
        return new DirectApplyService(metrics, auditLogger, transactionManager);
    }

    /**
     * Cursor and watermark.
     *
     * @param repository the state table
     * @return the service
     */
    @Bean
    @ConditionalOnMissingBean(TaskStateService.class)
    public TaskStateService reconciliationTaskStateService(SyncTaskStateRepository repository) {
        return new TaskStateService(repository);
    }

    /**
     * The three synchronous fetch shapes.
     *
     * @param executor   the shared fetch executor
     * @param metrics    instrumentation
     * @param rateLimits partner-scoped request-rate budgets
     * @param taskState  cursor checkpointing
     * @return the walker
     */
    @Bean
    @ConditionalOnMissingBean(FetchWalker.class)
    public FetchWalker reconciliationFetchWalker(
            @Qualifier("reconciliationFetchExecutor") ExecutorService executor,
            ReconciliationMetrics metrics,
            RateLimitRegistry rateLimits,
            TaskStateService taskState) {
        return new FetchWalker(executor, metrics, rateLimits, taskState);
    }

    /**
     * One run of one task's hot or cold pass.
     *
     * @param runLock        cluster-wide exclusion
     * @param walker         the fetch shapes
     * @param staging        writes outcomes down
     * @param directApply    applies them inline instead
     * @param taskState      cursor and watermark
     * @param repository     the staging table
     * @param metrics        instrumentation
     * @param auditLogger    the audit trail
     * @param correlationIds correlation for the run
     * @return the runner
     */
    @Bean
    @ConditionalOnMissingBean(TaskRunner.class)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public TaskRunner reconciliationTaskRunner(RunLock runLock,
                                               FetchWalker walker,
                                               StagingService staging,
                                               DirectApplyService directApply,
                                               TaskStateService taskState,
                                               SyncInboxRecordRepository repository,
                                               ReconciliationMetrics metrics,
                                               AuditSink auditLogger,
                                               CorrelationIdSource correlationIds) {
        return new TaskRunner(runLock, walker, staging, directApply, taskState, repository,
                metrics, auditLogger, correlationIds);
    }

    /**
     * Terminal-state handling for remote jobs.
     *
     * @param jobs               the job table
     * @param quotas             partner-scoped concurrency budgets
     * @param metrics            instrumentation
     * @param auditLogger        the audit trail
     * @param transactionManager the transaction manager
     * @return the settlement
     */
    @Bean
    @ConditionalOnMissingBean(RemoteJobSettlement.class)
    public RemoteJobSettlement reconciliationJobSettlement(SyncRemoteJobRepository jobs,
                                                           QuotaRegistry quotas,
                                                           ReconciliationMetrics metrics,
                                                           AuditSink auditLogger,
                                                           PlatformTransactionManager transactionManager) {
        return new RemoteJobSettlement(jobs, quotas, metrics, auditLogger, transactionManager);
    }

    /**
     * The asynchronous-job submit pass.
     *
     * @param jobs               the job table
     * @param records            the staging table
     * @param quotas             partner-scoped concurrency budgets
     * @param rateLimits         partner-scoped request-rate budgets
     * @param auditLogger        the audit trail
     * @param demandKeys         the demand-keys codec
     * @param owner              this instance's identity
     * @param transactionManager the transaction manager
     * @return the submit service
     */
    @Bean
    @ConditionalOnMissingBean(RemoteJobSubmitService.class)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public RemoteJobSubmitService reconciliationJobSubmitService(
            SyncRemoteJobRepository jobs,
            SyncInboxRecordRepository records,
            QuotaRegistry quotas,
            RateLimitRegistry rateLimits,
            AuditSink auditLogger,
            DemandKeys demandKeys,
            @Qualifier("reconciliationInstanceOwner") String owner,
            PlatformTransactionManager transactionManager) {
        return new RemoteJobSubmitService(jobs, records, quotas, rateLimits, auditLogger,
                demandKeys, owner, transactionManager);
    }

    /**
     * The asynchronous-job poll pass.
     *
     * @param jobs               the job table
     * @param quotas             partner-scoped concurrency budgets
     * @param settlement         terminal-state handling
     * @param auditLogger        the audit trail
     * @param owner              this instance's identity
     * @param transactionManager the transaction manager
     * @return the poll service
     */
    @Bean
    @ConditionalOnMissingBean(RemoteJobPollService.class)
    public RemoteJobPollService reconciliationJobPollService(
            SyncRemoteJobRepository jobs,
            QuotaRegistry quotas,
            RemoteJobSettlement settlement,
            AuditSink auditLogger,
            @Qualifier("reconciliationInstanceOwner") String owner,
            PlatformTransactionManager transactionManager) {
        return new RemoteJobPollService(jobs, quotas, settlement, auditLogger, owner, transactionManager);
    }

    /**
     * The asynchronous-job collect pass.
     *
     * @param jobs               the job table
     * @param staging            writes collected records down
     * @param settlement         terminal-state handling
     * @param quotas             partner-scoped concurrency budgets
     * @param owner              this instance's identity
     * @param transactionManager the transaction manager
     * @return the collect service
     */
    @Bean
    @ConditionalOnMissingBean(RemoteJobCollectService.class)
    public RemoteJobCollectService reconciliationJobCollectService(
            SyncRemoteJobRepository jobs,
            StagingService staging,
            RemoteJobSettlement settlement,
            QuotaRegistry quotas,
            @Qualifier("reconciliationInstanceOwner") String owner,
            PlatformTransactionManager transactionManager) {
        return new RemoteJobCollectService(jobs, staging, settlement, quotas, owner, transactionManager);
    }

    /**
     * Expiry and ambiguous-submit resolution.
     *
     * @param jobs               the job table
     * @param settlement         terminal-state handling
     * @param metrics            instrumentation
     * @param transactionManager the transaction manager
     * @return the maintenance service
     */
    @Bean
    @ConditionalOnMissingBean(RemoteJobMaintenanceService.class)
    public RemoteJobMaintenanceService reconciliationJobMaintenanceService(
            SyncRemoteJobRepository jobs,
            RemoteJobSettlement settlement,
            ReconciliationMetrics metrics,
            PlatformTransactionManager transactionManager) {
        return new RemoteJobMaintenanceService(jobs, settlement, metrics, transactionManager);
    }

    /**
     * The quota reclaim sweep.
     *
     * @param properties         the bound configuration
     * @param leases             the lease table
     * @param waiters            the queue table
     * @param jobs               the job table
     * @param registry           the discovered tasks
     * @param metrics            instrumentation
     * @param auditLogger        the audit trail
     * @param transactionManager the transaction manager
     * @return the reclaim service
     */
    @Bean
    @ConditionalOnMissingBean(QuotaReclaimService.class)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public QuotaReclaimService reconciliationQuotaReclaimService(
            ReconciliationProperties properties,
            QuotaLeaseRepository leases,
            QuotaWaiterRepository waiters,
            SyncRemoteJobRepository jobs,
            TaskRegistry registry,
            ReconciliationMetrics metrics,
            AuditSink auditLogger,
            PlatformTransactionManager transactionManager) {
        return new QuotaReclaimService(properties.getQuotas(), leases, waiters, jobs, registry,
                metrics, auditLogger, transactionManager);
    }

    /**
     * Builds and owns every scheduled pass.
     *
     * @param registry           the discovered tasks
     * @param runner             the fetch run
     * @param applyService       the apply pass
     * @param taskState          cursor and watermark
     * @param records            the staging table
     * @param submitService      the submit pass
     * @param pollService        the poll pass
     * @param collectService     the collect pass
     * @param maintenanceService expiry and ambiguity resolution
     * @param quotaReclaim       the quota reclaim sweep
     * @param quota              the quota, for its gauges
     * @param metrics            instrumentation
     * @param properties         the bound configuration
     * @param scheduler          the scheduler
     * @return the runtime
     */
    @Bean
    @ConditionalOnMissingBean(ReconciliationRuntime.class)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReconciliationRuntime reconciliationRuntime(
            TaskRegistry registry,
            TaskRunner runner,
            ApplyService applyService,
            TaskStateService taskState,
            SyncInboxRecordRepository records,
            RemoteJobSubmitService submitService,
            RemoteJobPollService pollService,
            RemoteJobCollectService collectService,
            RemoteJobMaintenanceService maintenanceService,
            QuotaReclaimService quotaReclaim,
            DatabaseQuota quota,
            ReconciliationMetrics metrics,
            ReconciliationProperties properties,
            @Qualifier("reconciliationTaskScheduler") TaskScheduler scheduler) {
        return new ReconciliationRuntime(registry, runner, applyService, taskState, records,
                submitService, pollService, collectService, maintenanceService, quotaReclaim,
                quota, metrics, properties, scheduler);
    }

    private static ObjectMapper defaultObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }

    /**
     * Scoped to this module's own packages, so it composes with whatever the consuming application
     * scans for its own entities and repositories - which is what makes the module usable without the
     * consumer configuring scanning for it at all.
     */
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = SyncInboxRecord.class)
    @EnableJpaRepositories(basePackageClasses = SyncInboxRecordRepository.class,
            repositoryBaseClass = BaseRepositoryImpl.class)
    static class ReconciliationJpaConfiguration {
    }
}
