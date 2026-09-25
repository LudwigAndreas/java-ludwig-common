package ru.ludwigandreas.export.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.ReportDefinitionSource;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.api.ReportWriterFactory;
import ru.ludwigandreas.export.enrich.EnrichmentExecutor;
import ru.ludwigandreas.export.engine.ReportRunEngine;
import ru.ludwigandreas.export.engine.TempFiles;
import ru.ludwigandreas.export.exception.ExportException;
import ru.ludwigandreas.export.format.csv.CsvReportWriterFactory;
import ru.ludwigandreas.export.format.xlsx.DirectoryXlsxTemplateSource;
import ru.ludwigandreas.export.format.xlsx.XlsxReportWriterFactory;
import ru.ludwigandreas.export.format.xlsx.XlsxTemplateSource;
import ru.ludwigandreas.export.i18n.ExportMessages;
import ru.ludwigandreas.export.metrics.ExportMetrics;
import ru.ludwigandreas.export.metrics.MicrometerExportMetrics;
import ru.ludwigandreas.export.metrics.NoopExportMetrics;
import ru.ludwigandreas.export.registry.MessageKeyValidator;
import ru.ludwigandreas.export.registry.ReportDefinitionRegistry;
import ru.ludwigandreas.export.sink.FilesystemReportSink;
import ru.ludwigandreas.restclient.config.ClientProperties;
import ru.ludwigandreas.restclient.config.ClientPropertiesMerger;
import ru.ludwigandreas.restclient.config.RestClientProperties;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;
import ru.ludwigandreas.webcore.problem.ProblemMessages;

/**
 * Wires the module, and does nothing at all until a service declares a report.
 *
 * <p>Every bean here is {@code @ConditionalOnMissingBean}, so a service replaces any one of them by
 * defining its own - a different sink, a registry seeded from somewhere else, a stricter message-key
 * check. The module is inert without configuration: with no
 * {@link ReportDefinitionSource} on the context the registry is empty, the validator confirms the
 * numbers are consistent, and nothing else is started.
 *
 * <p>The ordering here is not incidental. The registry is constructed from the writer factories that
 * are actually present, so a definition allowing a format nothing writes fails at startup rather
 * than at the first request; and the validator is constructed from the registry, so its per-stage
 * checks see the definitions rather than only the properties. Both are {@code @PostConstruct}-free
 * except the validator, which is where the cross-field failures are collected into one message.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExportProperties.class)
@ConditionalOnProperty(prefix = "ludwig.export", name = "enabled", matchIfMissing = true)
public class ExportAutoConfiguration {

    /** Bean name of the pool a stage's partner calls fan out on. */
    public static final String ENRICHMENT_EXECUTOR = "exportEnrichmentExecutor";

    /** Bean name of the pool a run's producer thread comes from. */
    public static final String PREFETCH_EXECUTOR = "exportPrefetchExecutor";

    /** Extra producer threads beyond the poller's, for runs executed on a request thread. */
    private static final int PREFETCH_SYNC_MARGIN = 2;

    /** Queued enrichment calls per thread before the pool starts running them on the caller. */
    private static final int QUEUE_PER_THREAD = 4;

    /**
     * Contributes this module's error text to the shared problem pipeline.
     *
     * <p>Lowest precedence, like every other module's bundle: the application's own
     * {@code MessageSource} is consulted first, so rewording any of these needs no fork and no
     * configuration - only the same key defined locally.
     */
    @Bean
    @ConditionalOnMissingBean(name = "exportProblemMessages")
    public ProblemMessageBundle exportProblemMessages() {
        return ProblemMessageBundle.of("i18n/ludwig-export-messages");
    }

    /**
     * Resolves definition message keys in every locale the estate ships.
     *
     * <p>Built on {@code web-core}'s {@link ProblemMessages} when one is on the context, so that a
     * service which overrode a column header in its own bundle is not told the key is missing. With
     * no problem pipeline present - a service that runs reports and exposes no REST layer - it falls
     * back to this module's own bundles, which is still enough to catch the mistake the check is for.
     */
    @Bean
    @ConditionalOnMissingBean
    public MessageKeyValidator exportMessageKeyValidator(ObjectProvider<ProblemMessages> messages) {
        ProblemMessages resolved = messages.getIfAvailable(
                () -> ProblemMessages.ofBundles("i18n/ludwig-export-messages"));
        return (key, locale) -> resolved.resolve(key, null, locale).isPresent();
    }

    /**
     * Resolves the text this module writes into files, in the locale a run asked for.
     *
     * <p>Backed by {@code web-core}'s resolution chain when one is present, so a service that
     * reworded a column header in its own bundle gets its wording rather than this module's. Falls
     * back to the key itself, which the registry has already made unreachable for any key a
     * definition declares.
     */
    @Bean
    @ConditionalOnMissingBean
    public ExportMessages exportMessages(ObjectProvider<ProblemMessages> messages) {
        ProblemMessages resolved = messages.getIfAvailable(
                () -> ProblemMessages.ofBundles("i18n/ludwig-export-messages"));
        return (key, locale, args) -> resolved.get(key, args, key, locale);
    }

    /**
     * The temp directory, and the sweep that removes what a killed instance left in it.
     *
     * <p>The sweep runs here, at bean creation, rather than on a schedule: the files it removes are
     * the ones this instance's <em>previous</em> life left behind, and anything written since is
     * either live or younger than the orphan age. Running it later would mean a restart loop never
     * reclaimed the disk it was restarting because of.
     */
    @Bean
    @ConditionalOnMissingBean
    public TempFiles exportTempFiles(ExportProperties properties, ObjectProvider<Clock> clock) {
        Path directory = resolveTempDirectory(properties);
        TempFiles tempFiles = new TempFiles(directory, clock.getIfAvailable(Clock::systemUTC));
        if (properties.getTemp().isSweepOnStartup()) {
            tempFiles.sweepOrphans(properties.getTemp().getOrphanAge());
        }
        return tempFiles;
    }

    /**
     * The CSV writer, and the worked example of the format seam.
     *
     * <p>Registered unconditionally rather than behind {@code formats.enabled}: that property says
     * which formats a <em>request</em> may ask for, and a format whose factory was missing entirely
     * would instead fail the registry at startup for every definition that allows it. Switching a
     * format off should narrow what users can ask for, not refuse to start the service.
     */
    @Bean
    @ConditionalOnMissingBean(name = "csvReportWriterFactory")
    public ReportWriterFactory csvReportWriterFactory(ExportProperties properties,
                                                      ExportMessages messages) {
        return new CsvReportWriterFactory(properties.getFormats().getCsv(), messages);
    }

    /**
     * Where an XLSX branding template comes from.
     *
     * <p>Resolves a name against the configured template directory and then the classpath, and
     * refuses anything that escapes either. See the class comment for why it re-reads the file per
     * run rather than caching it behind a watcher.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.apache.poi.xssf.streaming.SXSSFWorkbook")
    public XlsxTemplateSource xlsxTemplateSource(ExportProperties properties) {
        String configured = properties.getFormats().getXlsx().getTemplateDirectory();
        return new DirectoryXlsxTemplateSource(
                configured == null || configured.isBlank() ? null : Paths.get(configured));
    }

    /**
     * The XLSX writer, when POI is on the classpath.
     *
     * <p>Conditional on the class rather than on a property, because POI is an optional dependency:
     * a service that writes only CSV does not pay for it, and one that has it gets the format
     * without configuring anything. A definition that allows XLSX in a service without POI fails at
     * startup, in the registry, naming the definition - which is the right place to learn it.
     */
    @Bean
    @ConditionalOnMissingBean(name = "xlsxReportWriterFactory")
    @ConditionalOnClass(name = "org.apache.poi.xssf.streaming.SXSSFWorkbook")
    public ReportWriterFactory xlsxReportWriterFactory(ExportProperties properties,
                                                       XlsxTemplateSource templates,
                                                       ExportMessages messages) {
        return new XlsxReportWriterFactory(properties.getFormats().getXlsx(), templates, messages);
    }

    /**
     * The engine itself.
     *
     * @param factories every registered writer factory, including any a service added
     * @param sink      where finished files go
     * @param tempFiles where partial files live
     * @param messages  resolves headers, titles and markers
     * @param clock     injected so a run's wall-clock budget is deterministic in tests
     * @return the engine
     */
    @Bean
    @ConditionalOnMissingBean
    public ReportRunEngine reportRunEngine(ObjectProvider<ReportWriterFactory> factories,
                                           ReportSink sink, TempFiles tempFiles,
                                           ExportMessages messages, ObjectProvider<Clock> clock,
                                           EnrichmentExecutor enrichment, ExportMetrics metrics,
                                           @Qualifier(PREFETCH_EXECUTOR) ExecutorService prefetch) {
        return new ReportRunEngine(factories.orderedStream().toList(), sink, tempFiles, messages,
                clock.getIfAvailable(Clock::systemUTC), enrichment, metrics, prefetch);
    }

    /**
     * Where a stage's partner calls fan out.
     *
     * <p>Sized from {@code enrichment.concurrency} times the number of runs this instance executes
     * at once, because that is the most in-flight calls the configuration can ask for: each stage is
     * capped at its own concurrency by a semaphore, and the pool only has to be able to satisfy the
     * caps that are legal simultaneously. A caller-runs rejection policy means a saturated pool slows
     * a stage down rather than failing a report - the calls still happen, just on the run's own
     * thread, which is exactly the degradation a bounded fan-out is supposed to produce.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = ENRICHMENT_EXECUTOR)
    public ExecutorService exportEnrichmentExecutor(ExportProperties properties) {
        int threads = Math.max(1,
                properties.getEnrichment().getConcurrency() * properties.getPoller().getConcurrency());
        return boundedPool(threads, threads * QUEUE_PER_THREAD, "ludwig-export-enrich-");
    }

    /**
     * Where a run's producer thread comes from.
     *
     * <p>A separate pool from the enrichment one, and the separation is not tidiness: a producer
     * blocks waiting for the enrichment futures it submitted, so one shared pool would let producers
     * occupy every thread and then wait for work that can never be scheduled. That deadlock appears
     * only when enough runs are in flight at once, which is to say in production and not in a test.
     *
     * <p>One thread per run this instance may execute, plus a margin for the synchronous path, and a
     * queue of zero: a rejected submission is not a failure here, it makes the pump read on the
     * caller's thread, which loses the overlap and nothing else.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = PREFETCH_EXECUTOR)
    public ExecutorService exportPrefetchExecutor(ExportProperties properties) {
        int threads = properties.getPoller().getConcurrency() + PREFETCH_SYNC_MARGIN;
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), namedThreads("ludwig-export-prefetch-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Makes the per-run enrichment state: the cache, the catalogues and the degraded set.
     */
    @Bean
    @ConditionalOnMissingBean
    public EnrichmentExecutor exportEnrichmentExecutorFacade(
            ExportProperties properties, ExportMetrics metrics, ExportMessages messages,
            @Qualifier(ENRICHMENT_EXECUTOR) ExecutorService executor) {
        return new EnrichmentExecutor(executor, properties.getEnrichment(), metrics, messages);
    }

    /**
     * The module's meters, when Micrometer is present and instrumentation is switched on.
     *
     * <p>The no-op is a real bean rather than a null, so the engine has no null checks to forget.
     */
    @Bean
    @ConditionalOnMissingBean
    public ExportMetrics exportMetrics(ExportProperties properties,
                                       ObjectProvider<MeterRegistry> registry) {
        MeterRegistry meters = properties.getMetrics().isEnabled() ? registry.getIfAvailable() : null;
        return meters == null ? NoopExportMetrics.INSTANCE : new MicrometerExportMetrics(meters);
    }

    private ExecutorService boundedPool(int threads, int queue, String prefix) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(Math.max(1, queue)), namedThreads(prefix),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * Named, non-daemon threads.
     *
     * <p>Named because an unnamed pool in a thread dump is indistinguishable from every other
     * unnamed pool, and this is a module whose problems are usually diagnosed from a thread dump.
     * Non-daemon because a run in flight holds a lease and a temp file, and letting the JVM exit out
     * from under it would leave both behind for the reclaim to sort out.
     */
    private ThreadFactory namedThreads(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }

    /**
     * Collects every contributed definition and validates the estate.
     *
     * @param sources   the service's definition beans
     * @param factories the registered writer factories, whose formats bound what a definition may
     *                  allow
     * @param messageKeys resolves header and title keys in both locales
     * @return the immutable registry
     */
    @Bean
    @ConditionalOnMissingBean
    public ReportDefinitionRegistry reportDefinitionRegistry(
            ObjectProvider<ReportDefinitionSource> sources,
            ObjectProvider<ReportWriterFactory> factories,
            MessageKeyValidator messageKeys) {
        Map<String, ReportFormat> formats = new LinkedHashMap<>();
        factories.orderedStream().forEach(factory -> formats.put(factory.format().id(), factory.format()));
        return new ReportDefinitionRegistry(sources.orderedStream().toList(), formats, messageKeys);
    }

    /**
     * The filesystem sink, unless the service supplied one of its own.
     *
     * <p>Conditional on {@code ludwig.export.sink.type=filesystem}, which is the default, so an
     * estate that adds an object-storage sink switches to it with one property rather than by
     * excluding a bean.
     */
    @Bean
    @ConditionalOnMissingBean(ReportSink.class)
    @ConditionalOnProperty(prefix = "ludwig.export.sink", name = "type",
            havingValue = "filesystem", matchIfMissing = true)
    public ReportSink filesystemReportSink(ExportProperties properties) {
        String configured = properties.getSink().getDirectory();
        if (configured != null && !configured.isBlank()) {
            // A configured root must already exist, and the sink says so rather than creating it. A
            // typo in a path is otherwise indistinguishable from a correct one: the module would
            // happily create /var/tmp/catalog-reprots and write every file somebody later goes
            // looking for into it.
            return new FilesystemReportSink(Paths.get(configured));
        }
        // The unconfigured default is created, for the reason resolveTempDirectory creates its own:
        // refusing to start because nobody made a directory whose name they were never told would be
        // a poor first experience of the module.
        Path fallback = Paths.get(System.getProperty("java.io.tmpdir"), "ludwig-export");
        try {
            Files.createDirectories(fallback);
        } catch (IOException e) {
            throw new ExportException("Could not create the default export sink directory " + fallback, e);
        }
        return new FilesystemReportSink(fallback);
    }

    /**
     * Refuses to start on a configuration that is individually valid and jointly wrong.
     *
     * @param properties the bound configuration
     * @param factories  the registered writer factories
     * @param sinks      the registered sinks, by bean name, so the sink-type check can name what is
     *                   actually available
     * @param registry   the definitions, for the per-stage checks
     * @return the validator, which runs in {@code @PostConstruct}
     */
    @Bean
    @ConditionalOnMissingBean
    public ExportConfigurationValidator exportConfigurationValidator(
            ExportProperties properties,
            ObjectProvider<ReportWriterFactory> factories,
            Map<String, ReportSink> sinks,
            ReportDefinitionRegistry registry,
            ObjectProvider<RestClientPoolSizes> poolSizes) {
        Set<String> formatIds = new LinkedHashSet<>(
                factories.orderedStream().map(factory -> factory.format().id()).toList());
        Set<String> sinkTypes = sinkTypes(sinks);
        List<ReportDefinition<?, ?>> definitions = List.copyOf(registry.definitions());
        RestClientPoolSizes restClients = poolSizes.getIfAvailable(() -> RestClientPoolSizes.UNKNOWN);
        return new ExportConfigurationValidator(properties, formatIds, sinkTypes, definitions, restClients);
    }

    /**
     * Resolves a stage's declared REST client against the client the deployment actually configured.
     *
     * <p>Nested and conditional, because {@code rest-client-spring-boot-starter} is optional here: a
     * service whose reports need no enrichment carries none of it, and a member class is how this
     * module names {@code RestClientProperties} without loading it in that service.
     *
     * <p>The ceiling compared against is {@code max-per-route} rather than {@code max-total}, and
     * that is the number that bites. A named client has one base URL, so every call it makes is the
     * same route; a pool of fifty whose per-route cap is twenty sustains twenty concurrent calls, and
     * a stage asking for more gets a queue rather than an error.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClientProperties.class)
    public static class RestClientIntegration {

        /**
         * The pool-size resolver, reading the same merged properties the transport is built from.
         *
         * @param properties the REST-client configuration, or empty when the starter is on the
         *                   classpath without being enabled - in which case no client is configured
         *                   and every name is correctly unknown
         * @return the resolver
         */
        @Bean
        @ConditionalOnMissingBean
        public RestClientPoolSizes exportRestClientPoolSizes(
                ObjectProvider<RestClientProperties> properties) {
            RestClientProperties bound = properties.getIfAvailable();
            if (bound == null) {
                return RestClientPoolSizes.UNKNOWN;
            }
            return new RestClientPoolSizes() {
                @Override
                public OptionalInt forClient(String clientName) {
                    ClientProperties resolved = resolve(clientName);
                    if (resolved == null) {
                        return OptionalInt.empty();
                    }
                    Integer perRoute = resolved.getPool().getMaxPerRoute();
                    return perRoute == null ? OptionalInt.empty() : OptionalInt.of(perRoute);
                }

                @Override
                public Optional<String> authTypeOf(String clientName) {
                    ClientProperties resolved = resolve(clientName);
                    return resolved == null
                            ? Optional.empty()
                            : Optional.ofNullable(resolved.getAuth().getType());
                }

                /**
                 * The client as the transport will actually be built, not as the file spells it.
                 *
                 * <p>resolve() layers the named client over {@code ludwig.rest-client.defaults} over the
                 * module's built-in defaults, which matters for both questions asked here: a pool size
                 * and an auth type are both frequently set once in the defaults block.
                 */
                private ClientProperties resolve(String clientName) {
                    ClientProperties declared = bound.getClients().get(clientName);
                    return declared == null
                            ? null
                            : ClientPropertiesMerger.resolve(bound.getDefaults(), declared);
                }
            };
        }
    }

    /**
     * The directory partial files are written into, created when it was not configured.
     *
     * <p>A configured directory must already exist - the validator refuses one that does not, for
     * the reason stated there. The unconfigured default is this module's own subdirectory of the
     * JVM temp directory, which it does create: refusing to start because nobody made a directory
     * whose name they were never told would be a poor first experience of the module.
     */
    private Path resolveTempDirectory(ExportProperties properties) {
        String configured = properties.getTemp().getDirectory();
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        Path fallback = Paths.get(System.getProperty("java.io.tmpdir"), "ludwig-export-temp");
        try {
            Files.createDirectories(fallback);
        } catch (IOException e) {
            throw new ExportException("Could not create the default export temp directory " + fallback, e);
        }
        return fallback;
    }

    /**
     * What {@code sink.type} may legally name: the bean names of the sinks on the context, plus the
     * shipped sink's short name.
     *
     * <p>Deliberately without a fallback that adds the configured value itself. A validator that
     * accepted whatever was configured when it could find nothing to compare against would pass
     * every deployment, including the one this check exists for - a service that set
     * {@code sink.type} to a sink it forgot to contribute, and would discover it when the first
     * report finished and had nowhere to go.
     */
    private Set<String> sinkTypes(Map<String, ReportSink> sinks) {
        LinkedHashSet<String> types = new LinkedHashSet<>(sinks.keySet());
        if (sinks.containsKey("filesystemReportSink")) {
            types.add("filesystem");
        }
        if (sinks.containsKey("s3ReportSink")) {
            types.add("s3");
        }
        return types;
    }
}
