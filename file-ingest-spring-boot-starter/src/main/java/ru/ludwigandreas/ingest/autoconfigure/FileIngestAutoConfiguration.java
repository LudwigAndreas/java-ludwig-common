package ru.ludwigandreas.ingest.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import ru.ludwigandreas.ingest.api.FileIngest;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.ingest.bulk.CopyStagingWriter;
import ru.ludwigandreas.ingest.bulk.JdbcBatchStagingWriter;
import ru.ludwigandreas.ingest.bulk.StagingMerge;
import ru.ludwigandreas.ingest.bulk.StagingWriter;
import ru.ludwigandreas.ingest.config.FileIngestConfigurationValidator;
import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.ingest.engine.ArrivalDetector;
import ru.ludwigandreas.ingest.engine.FileIngestSchedules;
import ru.ludwigandreas.ingest.engine.BatchCommitter;
import ru.ludwigandreas.ingest.engine.IngestPass;
import ru.ludwigandreas.ingest.engine.IngestRunner;
import ru.ludwigandreas.ingest.engine.IngestScheduledTask;
import ru.ludwigandreas.ingest.engine.IngestTaskRegistry;
import ru.ludwigandreas.ingest.engine.MissingFileMonitor;
import ru.ludwigandreas.ingest.engine.ObjectDiscovery;
import ru.ludwigandreas.ingest.engine.ReceiptWriter;
import ru.ludwigandreas.ingest.engine.RegisteredIngestTask;
import ru.ludwigandreas.ingest.engine.SourceArchiver;
import ru.ludwigandreas.ingest.event.IngestEventPublisher;
import ru.ludwigandreas.ingest.event.NoopIngestEventPublisher;
import ru.ludwigandreas.ingest.metrics.IngestMetrics;
import ru.ludwigandreas.ingest.metrics.NoopIngestMetrics;
import ru.ludwigandreas.ingest.repository.FileIngestQuarantineRepository;
import ru.ludwigandreas.ingest.repository.FileIngestRunRepository;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;

/**
 * Wires the module, and does nothing at all until a service declares a task.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}, so a service replaces any one of them with its
 * own. With no {@code @IngestTask} bean on the context the registry is empty, the validator confirms
 * the configuration is consistent with that, no schedule is created and nothing runs.
 *
 * <p>The staging writer is the one bean chosen rather than merely defaulted: {@code AUTO} asks the
 * data source once, at startup, whether its connections are Postgres, and picks {@code COPY} if they
 * are. Asking at startup rather than per batch matters - a connection that is not Postgres is not
 * going to become one, and discovering it on the first batch of a two-hour run is the wrong moment.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FileIngestProperties.class)
@ConditionalOnProperty(prefix = "ludwig.ingest", name = "enabled", matchIfMissing = true)
public class FileIngestAutoConfiguration {

    /** Bean name of the scheduler this module's jobs register with. */
    public static final String TASK_SCHEDULER = "fileIngestTaskScheduler";

    /** Rows per JDBC batch when no task says otherwise; only the portable writer reads it. */
    private static final int DEFAULT_JDBC_BATCH_SIZE = 1000;

    /**
     * Contributes this module's error text to the shared problem pipeline.
     *
     * @return the bundle, at lowest precedence, so an application's own wording wins
     */
    @Bean
    @ConditionalOnMissingBean(name = "ingestProblemMessages")
    public ProblemMessageBundle ingestProblemMessages() {
        return ProblemMessageBundle.of("i18n/ludwig-ingest-messages");
    }

    /**
     * The clock every timestamp in this module comes from.
     *
     * @return the system clock, unless the application supplied one
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock fileIngestClock() {
        return Clock.systemUTC();
    }

    /**
     * The object mapper the receipt and the sentinel are read and written with.
     *
     * <p>Its own, rather than the application's, so that a service which configured its own mapper to
     * omit nulls or rename fields does not thereby change the shape of a receipt a partner parses.
     *
     * @return a mapper with JSR-310 support
     */
    @Bean
    @ConditionalOnMissingBean(name = "fileIngestObjectMapper")
    public ObjectMapper fileIngestObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature
                        .WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * The scheduler this module's cron entries and its monitor run on.
     *
     * <p>Its own pool, sized to the number of tasks plus the monitor, because a task that spends forty
     * minutes on a 2 GB file would otherwise delay everything else sharing the application's scheduler
     * - including things that have nothing to do with ingesting files.
     *
     * @param properties the bound configuration
     * @return the scheduler
     */
    @Bean(name = TASK_SCHEDULER, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = TASK_SCHEDULER)
    public TaskScheduler fileIngestTaskScheduler(FileIngestProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(properties.getTasks().size(), 1) + 1);
        scheduler.setThreadNamePrefix("file-ingest-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds((int) properties.getDrainTimeout().toSeconds());
        scheduler.initialize();
        return scheduler;
    }

    /**
     * Pairs each {@code @IngestTask} bean with its configuration block.
     *
     * @param beans      the annotated beans
     * @param properties the bound configuration
     * @return the registry
     */
    @Bean
    @ConditionalOnMissingBean(IngestTaskRegistry.class)
    public IngestTaskRegistry ingestTaskRegistry(ObjectProvider<FileIngest<?>> beans,
                                                 FileIngestProperties properties) {
        List<RegisteredIngestTask> registered = new ArrayList<>();
        beans.orderedStream().forEach(bean -> {
            FileIngestProperties.Task settings = properties.getTasks().get(bean.name());
            if (settings != null) {
                registered.add(new RegisteredIngestTask(bean.name(), bean, settings));
            }
        });
        return new IngestTaskRegistry(registered);
    }

    /**
     * The startup validator.
     *
     * @param properties the bound configuration
     * @param beans      the annotated beans, so a bean with no configuration is reported
     * @param outbox     whether an outbox publisher is present
     * @return the validator, which runs in {@code @PostConstruct}
     */
    @Bean
    @ConditionalOnMissingBean(FileIngestConfigurationValidator.class)
    public FileIngestConfigurationValidator fileIngestConfigurationValidator(
            FileIngestProperties properties, ObjectProvider<FileIngest<?>> beans,
            ObjectProvider<IngestEventPublisher> outbox) {
        Map<String, FileIngest<?>> byName = new LinkedHashMap<>();
        beans.orderedStream().forEach(bean -> byName.put(bean.name(), bean));
        boolean outboxAvailable = outbox.stream()
                .anyMatch(publisher -> !(publisher instanceof NoopIngestEventPublisher));
        return new FileIngestConfigurationValidator(properties, byName, outboxAvailable);
    }

    /**
     * How rows reach the staging table.
     *
     * @param dataSource the application's data source
     * @param properties the bound configuration
     * @return the writer
     */
    @Bean
    @ConditionalOnMissingBean(StagingWriter.class)
    public StagingWriter ingestStagingWriter(DataSource dataSource, FileIngestProperties properties) {
        FileIngestProperties.StagingWriterType configured = properties.getTasks().values().stream()
                .map(task -> task.getWrite().getStagingWriter())
                .findFirst()
                .orElse(FileIngestProperties.StagingWriterType.AUTO);
        int batchSize = properties.getTasks().values().stream()
                .map(task -> task.getWrite().getJdbcBatchSize())
                .findFirst()
                .orElse(DEFAULT_JDBC_BATCH_SIZE);
        boolean useCopy = switch (configured) {
            case COPY -> true;
            case JDBC_BATCH -> false;
            case AUTO -> CopyStagingWriter.supports(dataSource);
        };
        if (useCopy) {
            log.info("File ingest will stage rows with Postgres COPY");
            return new CopyStagingWriter(dataSource);
        }
        log.info("File ingest will stage rows with batched INSERT ({} rows per batch)", batchSize);
        return new JdbcBatchStagingWriter(dataSource, batchSize);
    }

    /**
     * The staging-to-target merge.
     *
     * @param dataSource the application's data source
     * @return the merge runner
     */
    @Bean
    @ConditionalOnMissingBean(StagingMerge.class)
    public StagingMerge ingestStagingMerge(DataSource dataSource) {
        return new StagingMerge(dataSource);
    }

    /**
     * The batch-and-checkpoint transaction.
     *
     * @param runs          the run table
     * @param quarantines   the quarantine table
     * @param stagingWriter how rows reach staging
     * @return the committer
     */
    @Bean
    @ConditionalOnMissingBean(BatchCommitter.class)
    public BatchCommitter ingestBatchCommitter(FileIngestRunRepository runs,
                                               FileIngestQuarantineRepository quarantines,
                                               StagingWriter stagingWriter) {
        return new BatchCommitter(runs, quarantines, stagingWriter);
    }

    /**
     * The metrics, defaulting to the no-op so the engine has no null to check.
     *
     * @return the no-op recorder
     */
    @Bean
    @ConditionalOnMissingBean(IngestMetrics.class)
    public IngestMetrics ingestMetrics() {
        return new NoopIngestMetrics();
    }

    /**
     * The completion event publisher, defaulting to the no-op.
     *
     * @return the no-op publisher
     */
    @Bean
    @ConditionalOnMissingBean(IngestEventPublisher.class)
    public IngestEventPublisher ingestEventPublisher() {
        return new NoopIngestEventPublisher();
    }

    /**
     * Object discovery.
     *
     * @param store the platform's object store
     * @return the discovery
     */
    @Bean
    @ConditionalOnMissingBean(ObjectDiscovery.class)
    public ObjectDiscovery ingestObjectDiscovery(ObjectStore store) {
        return new ObjectDiscovery(store);
    }

    /**
     * Arrival detection.
     *
     * @param store        the platform's object store
     * @param objectMapper for a sentinel carrying a record count
     * @return the detector
     */
    @Bean
    @ConditionalOnMissingBean(ArrivalDetector.class)
    public ArrivalDetector ingestArrivalDetector(ObjectStore store, ObjectMapper objectMapper) {
        return new ArrivalDetector(store, objectMapper);
    }

    /**
     * The receipt writer.
     *
     * @param store        the platform's object store
     * @param objectMapper how the summary becomes JSON
     * @return the writer
     */
    @Bean
    @ConditionalOnMissingBean(ReceiptWriter.class)
    public ReceiptWriter ingestReceiptWriter(ObjectStore store, ObjectMapper objectMapper) {
        return new ReceiptWriter(store, objectMapper);
    }

    /**
     * The source archiver.
     *
     * @param store the platform's object store
     * @return the archiver
     */
    @Bean
    @ConditionalOnMissingBean(SourceArchiver.class)
    public SourceArchiver ingestSourceArchiver(ObjectStore store) {
        return new SourceArchiver(store);
    }

    /**
     * The run loop.
     *
     * @param store         the platform's object store
     * @param runs          the run table
     * @param committer     the batch-and-checkpoint transaction
     * @param stagingWriter how rows reach staging
     * @param merge         the staging-to-target merge
     * @param receipts      the receipt writer
     * @param archiver      the source archiver
     * @param metrics       the metrics
     * @param audit         the audit sink
     * @param clock         the clock
     * @return the runner
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a @Bean method whose arguments are all named beans; there
    // is no positional call site for the rule to protect.
    @Bean
    @ConditionalOnMissingBean(IngestRunner.class)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public IngestRunner ingestRunner(ObjectStore store, FileIngestRunRepository runs,
                                     BatchCommitter committer, StagingWriter stagingWriter,
                                     StagingMerge merge, ReceiptWriter receipts,
                                     SourceArchiver archiver, IngestMetrics metrics,
                                     AuditSink audit, Clock clock) {
        return new IngestRunner(store, runs, committer, stagingWriter, merge, receipts, archiver,
                metrics, audit, clock);
    }

    /**
     * One pass.
     *
     * @param registry  the configured tasks
     * @param discovery object discovery
     * @param arrival   arrival detection
     * @param runner    the run loop
     * @param runs      the run table
     * @param store     the platform's object store
     * @param lock      the platform's one distributed lock
     * @param metrics   the metrics
     * @param audit     the audit sink
     * @param events    the completion publisher
     * @param clock     the clock
     * @return the pass
     */
    @Bean
    @ConditionalOnMissingBean(IngestPass.class)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public IngestPass ingestPass(IngestTaskRegistry registry, ObjectDiscovery discovery,
                                 ArrivalDetector arrival, IngestRunner runner,
                                 FileIngestRunRepository runs, ObjectStore store, RunLock lock,
                                 IngestMetrics metrics, AuditSink audit,
                                 IngestEventPublisher events, Clock clock) {
        return new IngestPass(registry, discovery, arrival, runner, runs, store, lock, metrics, audit,
                events, clock);
    }

    /**
     * One cron schedule per task, plus the missing-file monitor, owned by one lifecycle.
     *
     * <p>The number of schedules is the number of configured tasks and is not known until the
     * properties are bound, so they cannot be individual {@code @Bean} methods - and a {@code @Bean}
     * returning a bare {@code List} of them would be one bean Spring never looks inside, so nothing
     * would ever be scheduled and nothing would say so. See {@link FileIngestSchedules}.
     *
     * @param registry      the configured tasks
     * @param pass          what a firing does
     * @param runs          the run table, for the monitor
     * @param metrics       the metrics, for the monitor's gauge
     * @param audit         the audit sink
     * @param taskScheduler this module's scheduler
     * @param properties    the bound configuration
     * @param clock         the clock
     * @return the lifecycle Spring starts and drains
     */
    @Bean
    @ConditionalOnMissingBean(FileIngestSchedules.class)
    @ConditionalOnProperty(prefix = "ludwig.ingest", name = "scheduler-enabled",
            matchIfMissing = true)
    @SuppressWarnings("checkstyle:ParameterNumber")
    public FileIngestSchedules fileIngestSchedules(IngestTaskRegistry registry, IngestPass pass,
                                                   FileIngestRunRepository runs,
                                                   IngestMetrics metrics, AuditSink audit,
                                                   @Qualifier(TASK_SCHEDULER) TaskScheduler taskScheduler,
                                                   FileIngestProperties properties, Clock clock) {
        List<SelfSchedulingLifecycle> lifecycles = new ArrayList<>();
        registry.all().stream()
                .filter(task -> task.settings().isEnabled())
                .forEach(task -> lifecycles.add(new IngestScheduledTask(task.name(),
                        task.settings().getSchedule().getCron(), taskScheduler,
                        properties.getDrainTimeout(), pass)));
        lifecycles.add(new MissingFileMonitor(registry, runs, metrics, audit, taskScheduler,
                properties.getDrainTimeout(), clock));
        return new FileIngestSchedules(lifecycles);
    }
}
