package ru.ludwigandreas.fileaction.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.CorrelationProvider;
import ru.ludwigandreas.fileaction.api.FileAction;
import ru.ludwigandreas.fileaction.api.FileActionHandler;
import ru.ludwigandreas.fileaction.api.FileScanner;
import ru.ludwigandreas.fileaction.engine.ApplyPass;
import ru.ludwigandreas.fileaction.engine.AuthorityChecker;
import ru.ludwigandreas.fileaction.engine.BindingPass;
import ru.ludwigandreas.fileaction.engine.BoundRowStore;
import ru.ludwigandreas.fileaction.engine.DeferredSubmissionWorker;
import ru.ludwigandreas.fileaction.engine.ErrorReportPublisher;
import ru.ludwigandreas.fileaction.engine.FileActionRegistry;
import ru.ludwigandreas.fileaction.engine.FileActionService;
import ru.ludwigandreas.fileaction.engine.FileActionSettings;
import ru.ludwigandreas.fileaction.engine.RetentionJob;
import ru.ludwigandreas.fileaction.engine.RowReaderFactoryRegistry;
import ru.ludwigandreas.fileaction.engine.SubmissionStore;
import ru.ludwigandreas.fileaction.format.FormatSniffer;
import ru.ludwigandreas.fileaction.format.RowReaderFactory;
import ru.ludwigandreas.fileaction.format.csv.CsvRowReaderFactory;
import ru.ludwigandreas.fileaction.format.csv.RejectCsvWriter;
import ru.ludwigandreas.fileaction.format.xlsx.read.XlsxRowReaderFactory;
import ru.ludwigandreas.fileaction.format.xlsx.write.AnnotatedReportWriter;
import ru.ludwigandreas.fileaction.format.xlsx.write.TemplateWriter;
import ru.ludwigandreas.fileaction.repository.FileActionRowRejectRepository;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;
import ru.ludwigandreas.fileaction.web.FileActionController;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.ScheduledJob;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;
import ru.ludwigandreas.webcore.problem.ProblemMessages;

/**
 * Wires the module.
 *
 * <h2>What the conditions are for</h2>
 *
 * <p>Nothing here is conditional on taste. Each guard corresponds to a dependency the module declares optional,
 * and the module genuinely works without it: no {@code RunLock} means no retention job, no {@code TaskScheduler}
 * means no deferred worker. What is <em>not</em> optional is the configuration being consistent -
 * {@link FileActionConfigurationValidator} runs unconditionally and refuses the application - because a
 * misconfiguration here has business data as its symptom.
 *
 * <h2>Why the registry is a bean and not a lazy lookup</h2>
 *
 * <p>Building it is what validates the configuration, so it has to happen at startup. A lazily resolved registry
 * would move every one of the validator's refusals from "the application did not start" to "the first user who
 * dragged a file in got a 500", which is the whole thing this module is trying not to do.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ludwig.file-action", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(FileActionProperties.class)
public class FileActionAutoConfiguration {

    /**
     * Bean name of the scheduler this module's two jobs register themselves with.
     *
     * <p>Its own, named, and injected by name - not a {@code TaskScheduler} taken by type. A consuming service that
     * also has {@code idempotency} and {@code outbox} on its classpath has three schedulers in its context, and an
     * injection by type then fails at startup with "expected single matching bean but found 3". That is exactly what
     * happened: this module injected by type, and {@code FileActionSchemaIT} - the first context with the other
     * starters present - would not start. Every scheduled starter here owns a named scheduler for this reason.
     *
     * <p>A separate pool is also the right answer on its own terms: a file action's apply can take minutes, and
     * sharing a single-threaded pool with the idempotency purge would mean one large import stops the purge.
     */
    public static final String TASK_SCHEDULER = "fileActionTaskScheduler";

    /**
     * The scheduler this module's jobs run on.
     *
     * @return the scheduler
     */
    @Bean(name = TASK_SCHEDULER)
    @ConditionalOnMissingBean(name = TASK_SCHEDULER)
    public TaskScheduler fileActionTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // Two threads: the deferred worker and the retention job, so a long import does not delay retention and a
        // retention pass holding the lock does not delay an import.
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ludwig-file-action-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * The clock this module's components take their timestamps from.
     *
     * <p><strong>Not a bean.</strong> It was one, guarded by {@code @ConditionalOnMissingBean}, and that is the
     * trap {@code idempotency} and {@code audit} already document for {@code JPAQueryFactory}: such a guard is
     * not safe in a library. {@code rest-client-spring-boot-starter} publishes {@code ludwigRestClientClock},
     * so a service with both starters had two {@code Clock} beans and <em>anything</em> injecting one by type -
     * {@code cache-spring-boot-starter}'s registry, as it turned out - failed to start on an ambiguity neither
     * module caused alone. {@code crud-service-example} found it; nothing in this module's own suite could.
     *
     * <p>So the module resolves one instead of publishing one: the application's clock if it has exactly one,
     * and {@code systemUTC} otherwise. {@code systemUTC} and never {@code systemDefaultZone}, because
     * {@code RuleGroup.PRESENTATION} forbids the latter and the reason is the reason - the JVM default zone is
     * the container's, which is UTC in the datacentre and the developer's own on a laptop, so a timestamp taken
     * from it is wrong exactly where nobody is looking.
     *
     * @param clock the application's clock, if it has one and only one
     * @return the clock to use
     */
    private static Clock resolveClock(ObjectProvider<Clock> clock) {
        Clock application = clock.getIfUnique();
        return application == null ? Clock.systemUTC() : application;
    }

    /** The CSV reader. */
    @Bean
    public CsvRowReaderFactory csvRowReaderFactory() {
        return new CsvRowReaderFactory();
    }

    /** The workbook reader. */
    @Bean
    public XlsxRowReaderFactory xlsxRowReaderFactory() {
        return new XlsxRowReaderFactory();
    }

    /** The reader factories, indexed by format and checked for completeness at startup. */
    @Bean
    public RowReaderFactoryRegistry rowReaderFactoryRegistry(List<RowReaderFactory> factories) {
        return new RowReaderFactoryRegistry(factories);
    }

    /** What the content actually is. */
    @Bean
    public FormatSniffer formatSniffer() {
        return new FormatSniffer();
    }

    /** The template generator. */
    @Bean
    public TemplateWriter templateWriter() {
        return new TemplateWriter();
    }

    /** The annotated-workbook writer. */
    @Bean
    public AnnotatedReportWriter annotatedReportWriter() {
        return new AnnotatedReportWriter();
    }

    /** The reject CSV writer. */
    @Bean
    public RejectCsvWriter rejectCsvWriter() {
        return new RejectCsvWriter();
    }

    /** How bound rows are written and read. */
    @Bean
    public BoundRowStore boundRowStore(ObjectMapper mapper) {
        return new BoundRowStore(mapper);
    }

    /** This module's message bundle, contributed into web-core's one ProblemDetail pipeline. */
    @Bean
    public ProblemMessageBundle fileActionProblemMessages() {
        return ProblemMessageBundle.of("i18n/ludwig-file-action-messages");
    }

    /**
     * Resolves the configuration against the handler beans and refuses the application when they disagree.
     *
     * <p>Takes the {@link ApplicationContext} rather than a {@code List<FileActionHandler<?>>} because the
     * action name is on the {@link FileAction} annotation, and a list of beans does not carry their annotations.
     *
     * @param properties  the configured tree
     * @param context     the context, for the annotated beans
     * @param scanner     the deployment's scanner, if any
     * @param validator   the Bean Validation validator, if any
     * @param messages    the resolved message bundles
     * @return the registry
     */
    @Bean
    public FileActionRegistry fileActionRegistry(FileActionProperties properties,
                                                ApplicationContext context,
                                                ObjectProvider<FileScanner> scanner,
                                                ObjectProvider<Validator> validator,
                                                ProblemMessages messages) {
        boolean securityPresent = isPresent(
                "ru.ludwigandreas.security.authorization.AuthorityEvaluator")
                || isPresent("org.springframework.security.core.Authentication");
        return new FileActionConfigurationValidator(properties, handlersByAction(context),
                scanner.getIfAvailable(), validator.getIfAvailable(), securityPresent,
                code -> resolvesInEveryLocale(messages, code))
                .validate();
    }

    /**
     * Whether a code resolves in both locales this module ships.
     *
     * <p>Both, not the default one. A key present in English and missing in Russian produces a report that is
     * half readable, and nobody notices until a Russian-speaking user opens one.
     */
    private static boolean resolvesInEveryLocale(ProblemMessages messages, String code) {
        return messages.resolve(code, new Object[0], java.util.Locale.ENGLISH).isPresent()
                && messages.resolve(code, new Object[0], java.util.Locale.forLanguageTag("ru")).isPresent();
    }

    private static Map<String, FileActionHandler<?>> handlersByAction(ApplicationContext context) {
        Map<String, FileActionHandler<?>> byAction = new LinkedHashMap<>();
        List<String> wrongType = new ArrayList<>();
        for (Object bean : context.getBeansWithAnnotation(FileAction.class).values()) {
            FileAction annotation = AnnotationUtils.findAnnotation(bean.getClass(), FileAction.class);
            if (annotation == null) {
                continue;
            }
            if (!(bean instanceof FileActionHandler<?> handler)) {
                // The annotation cannot express this in its own type, so it is checked here. An annotation whose
                // contract is only written down is one that gets put on the wrong bean eventually.
                wrongType.add(bean.getClass().getName());
                continue;
            }
            byAction.put(annotation.value(), handler);
        }
        if (!wrongType.isEmpty()) {
            throw new IllegalStateException("these beans are annotated @FileAction but do not implement"
                    + " FileActionHandler, so there is nothing for the module to call: " + wrongType);
        }
        return byAction;
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, FileActionAutoConfiguration.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    /**
     * Reads the current caller's authorities out of Spring Security.
     *
     * <p>Conditional on the class, because {@code security-spring-boot-starter} is an optional dependency and a
     * deployment with no security and no configured authority is a valid deployment. There is deliberately no
     * permissive fallback for the case where security is absent: that would turn a configured authority into one
     * that silently is not checked. {@link FileActionConfigurationValidator} refuses that combination at startup
     * instead.
     *
     * <p>Both spellings are accepted. Spring Security's convention is that a role is an authority prefixed
     * {@code ROLE_}, and a deployment writing {@code required-authority: CATALOG_ADMIN} against a principal
     * carrying {@code ROLE_CATALOG_ADMIN} is making the commonest mistake there is - one that presents as a 403
     * nobody can explain. Accepting the prefix in either direction is the behaviour {@code hasRole} already has.
     *
     * @return the checker
     */
    @Bean
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    @ConditionalOnMissingBean
    public AuthorityChecker fileActionAuthorityChecker() {
        return authority -> {
            var authentication = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) {
                return false;
            }
            String bare = authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority;
            String prefixed = "ROLE_" + bare;
            return authentication.getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .anyMatch(granted -> granted.equals(authority) || granted.equals(bare)
                            || granted.equals(prefixed));
        };
    }

    /** Every write to this module's two tables. */
    @Bean
    public SubmissionStore submissionStore(FileActionSubmissionRepository submissions,
                                          FileActionRowRejectRepository rejects,
                                          ObjectProvider<Clock> clock) {
        return new SubmissionStore(submissions, rejects, resolveClock(clock));
    }

    /** The read-and-bind half. */
    @Bean
    public BindingPass bindingPass(RowReaderFactoryRegistry readers, BoundRowStore boundRows) {
        return new BindingPass(readers, boundRows);
    }

    /** The apply half. */
    @Bean
    public ApplyPass applyPass(BoundRowStore boundRows, PlatformTransactionManager transactions) {
        return new ApplyPass(boundRows, new TransactionTemplate(transactions));
    }

    /** How a reject report is produced and uploaded. */
    @Bean
    public ErrorReportPublisher errorReportPublisher(ObjectStore objectStore,
                                                     FileActionSettings settings,
                                                     AnnotatedReportWriter workbooks, RejectCsvWriter csvs,
                                                     ProblemMessages messages) {
        return new ErrorReportPublisher(objectStore, settings, workbooks, csvs,
                (problem, context) -> messages.get(problem.code(), problem.args().toArray(),
                        problem.code(), context.preferences().locale()));
    }

    /**
     * The module-wide settings, resolved out of the configuration tree once.
     *
     * <p>A separate bean so that the engine takes settings rather than configuration, which is what keeps the
     * {@code engine} package free of a dependency on {@code config} - ArchUnit's cycle rule found that edge, and
     * it was right about more than the cycle. See {@link FileActionSettings}.
     *
     * @param properties the configured tree
     * @return the resolved settings
     */
    @Bean
    public FileActionSettings fileActionSettings(FileActionProperties properties) {
        return new FileActionSettings(properties.getStorage().getUploads(),
                properties.getStorage().getArtifacts(), properties.getStorage().getRetention(),
                properties.getScanning().getMode());
    }

    /** The engine. */
    // SUPPRESS CHECKSTYLE ParameterNumber - a factory method mirroring a constructor the service needs.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    public FileActionService fileActionService(FileActionRegistry registry, FileActionSettings settings,
                                              ObjectStore objectStore,
                                              FileActionSubmissionRepository submissions,
                                              FileActionRowRejectRepository rejects,
                                              TemplateWriter templates, SubmissionStore store,
                                              BindingPass bindingPass, ApplyPass applyPass,
                                              BoundRowStore boundRows, FormatSniffer sniffer,
                                              AuditSink audit, ActorResolver actors,
                                              CorrelationProvider correlation,
                                              ObjectProvider<FileScanner> scanner,
                                              ObjectProvider<AuthorityChecker> authorities,
                                              ErrorReportPublisher reports,
                                              ObjectProvider<Clock> clock) {
        return new FileActionService(registry, settings, objectStore, submissions, rejects, templates,
                store, bindingPass, applyPass, boundRows, sniffer, audit, actors, correlation,
                scanner.getIfAvailable(), authorities.getIfAvailable(), reports, resolveClock(clock));
    }

    /** The HTTP surface. */
    @Bean
    @ConditionalOnClass(name = "org.springframework.web.multipart.MultipartFile")
    public FileActionController fileActionController(FileActionService service,
                                                    FileActionRegistry registry, ObjectStore objectStore,
                                                    ProblemMessages messages) {
        return new FileActionController(service, registry, objectStore, messages);
    }

    /**
     * The deferred worker, scheduled on the application's own task scheduler.
     *
     * <p>Not {@code @Scheduled}: that annotation takes its interval as an attribute, which means a placeholder
     * re-parsed from configuration already bound to a {@code Duration}, and it requires the consuming
     * application to have enabled scheduling - so a starter using it would silently do nothing. {@code job-core}
     * exists for this reason and {@code ScheduledJob} schedules itself.
     *
     * @param registry    the resolved actions
     * @param submissions the submission table
     * @param store       the state transitions
     * @param service     the engine
     * @param objectStore where the bytes are
     * @param properties  the worker's tuning
     * @param scheduler   the application's scheduler
     * @param clock       the clock
     * @return the job
     */
    @Bean
    @ConditionalOnMissingBean(name = "fileActionDeferredJob")
    public ScheduledJob fileActionDeferredJob(FileActionRegistry registry,
                                              FileActionSubmissionRepository submissions,
                                              SubmissionStore store, FileActionService service,
                                              ObjectStore objectStore, FileActionProperties properties,
                                              @Qualifier(TASK_SCHEDULER) TaskScheduler scheduler,
                                              ObjectProvider<Clock> clock) {
        FileActionProperties.Deferred tuning = properties.getDeferred();
        DeferredSubmissionWorker worker = new DeferredSubmissionWorker(registry, submissions, store,
                service, objectStore, ru.ludwigandreas.job.core.claim.ClaimOwner.resolve(null),
                tuning.getLease(), tuning.getBatchSize(), tuning.getMaxAttempts(), resolveClock(clock));
        return new ScheduledJob("file-action-deferred", scheduler,
                ScheduleSpec.fixedDelay(tuning.getInterval(), tuning.getInitialDelay()),
                tuning.getDrainTimeout(), worker::pollOnce);
    }

    /**
     * The retention job, which needs the platform's one distributed lock.
     *
     * @param submissions the submission table
     * @param store       the state transitions
     * @param objectStore where the bytes are
     * @param runLock     the platform's one distributed lock
     * @param audit       the platform's one audit sink
     * @param properties  the configured retention settings
     * @param scheduler   the application's scheduler
     * @param clock       the clock
     * @return the job
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a factory method mirroring the job's constructor.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    @ConditionalOnMissingBean(name = "fileActionRetentionJob")
    public ScheduledJob fileActionRetentionJob(FileActionSubmissionRepository submissions,
                                               SubmissionStore store, ObjectStore objectStore,
                                               RunLock runLock, AuditSink audit,
                                               FileActionProperties properties,
                                               @Qualifier(TASK_SCHEDULER) TaskScheduler scheduler,
                                               ObjectProvider<Clock> clock) {
        RetentionJob job = new RetentionJob(submissions, store, objectStore, runLock, audit,
                properties.getStorage().getRetentionBatchSize(),
                properties.getStorage().getRetentionLease(), resolveClock(clock));
        return new ScheduledJob("file-action-retention", scheduler,
                ScheduleSpec.fixedDelay(properties.getStorage().getRetentionInterval(),
                        properties.getDeferred().getInitialDelay()),
                properties.getDeferred().getDrainTimeout(), job::pollOnce);
    }
}
