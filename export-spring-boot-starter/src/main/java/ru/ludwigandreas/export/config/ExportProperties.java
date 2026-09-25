package ru.ludwigandreas.export.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.ludwigandreas.export.api.FailurePolicy;
import ru.ludwigandreas.export.api.CallIdentity;
import ru.ludwigandreas.export.api.MissingPolicy;

/**
 * Everything this module can be told, under {@code ludwig.export}.
 *
 * <h2>The five numbers that decide whether a million-row report fits in the heap</h2>
 *
 * <p>{@code source.page-size}, {@code window-size}, {@code enrichment.batch-size},
 * {@code enrichment.concurrency} and the writer's flush window are not independent knobs, and the
 * defaults below are a set rather than five separate choices. The rough shape of the peak is
 *
 * <pre>
 *   window-size rows
 * + window-size x (enriched columns) values from the partners
 * + queue-depth x window-size rows waiting to be written
 * + the writer's flush window
 * + the enrichment cache
 * </pre>
 *
 * <p>which is why {@link ExportConfigurationValidator} rejects combinations that are individually
 * legal and jointly wrong - a window smaller than the batch it is supposed to fill, a cache smaller
 * than one batch, a concurrency larger than the REST client's connection pool. Raising one of these
 * without the others is the usual way a service that was comfortable at 100,000 rows falls over at a
 * million.
 *
 * <p>Defaults are sized for the design point this module was built against: 1,000,000 rows x 25
 * columns, of which up to 8 come from enrichment across up to 4 partner services. The measured
 * baseline is in the README.
 *
 * @see ExportConfigurationValidator for the cross-field checks that run at startup
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ludwig.export")
public class ExportProperties {

    /** Master switch for the whole module's autoconfiguration. */
    private boolean enabled = true;

    /**
     * Identity written into a run's {@code claimed_by}. Defaults to the shared {@code job-core}
     * instance identity, which is what keeps one pod looking like one instance across every module
     * that claims rows.
     */
    private String instance;

    /**
     * Rows read from the database per keyset page.
     *
     * <p>Smaller than the window on purpose: the page is what the database materialises and what the
     * persistence context holds until it is cleared, and a page the size of the window would put the
     * whole window in the persistence context as well as in the engine's own buffer.
     */
    @Min(1)
    private int sourcePageSize = 1_000;

    /**
     * Rows enriched and handed to the writer as one unit.
     *
     * <p>The unit of the memory bound, of cancellation latency (a cancelled run stops within one
     * window) and of the enrichment fan-out. Larger windows mean fewer, better-packed partner
     * batches and a coarser bound; smaller windows mean the opposite.
     */
    @Min(1)
    private int windowSize = 2_000;

    /**
     * Windows that may sit between the enrichment side and the writer.
     *
     * <p>This is the backpressure. The writer is the slowest stage for CSV and the enricher for
     * XLSX, so whichever is behind has to be able to stop the other; a queue with no depth would
     * serialise the two stages and lose the overlap, and an unbounded one would let the source run
     * ahead until the heap ran out. Four is enough to keep both sides busy through a partner's
     * latency spike and small enough to stay inside the bound.
     */
    @Min(1)
    private int handoffQueueDepth = 4;

    /**
     * Rows at or below which a run executes on the request thread rather than being handed to the
     * poller.
     *
     * <p>One code path, two exits: the same engine runs either way, and this only decides whether
     * the caller waits. Set it to zero to make every run asynchronous, which is the right setting
     * for a service whose report requests arrive on the same thread pool as its interactive traffic.
     */
    @Min(0)
    private long syncThresholdRows = 5_000;

    /** How long a run may take before it is failed, whichever exit it took. */
    @NotNull
    private Duration wallClockBudget = Duration.ofMinutes(30);

    /**
     * Estate-wide ceiling on rows per run, applied on top of each definition's own {@code maxRows}.
     *
     * <p>Two ceilings rather than one because they answer different questions: a definition's cap is
     * "this report should never be this big", and this one is "this service will not spend that much
     * of itself on any report". The lower of the two wins.
     */
    @Min(1)
    private long maxRowsPerRun = 5_000_000;

    /** Enrichment defaults, overridable per stage in the definition. */
    @Valid
    private final Enrichment enrichment = new Enrichment();

    /** Temp files and the sweep that removes the ones a crash left behind. */
    @Valid
    private final Temp temp = new Temp();

    /** Which formats the estate allows at all, and their per-format settings. */
    @Valid
    private final Formats formats = new Formats();

    /** Where finished files go, and how long they stay. */
    @Valid
    private final Sink sink = new Sink();

    /** The asynchronous run poller. */
    @Valid
    private final Poller poller = new Poller();

    /** Per-user limits on how much reporting one person can be doing at once. */
    @Valid
    private final Quota quota = new Quota();

    /** Whether this module applies its own changelog. */
    @Valid
    private final Liquibase liquibase = new Liquibase();

    /** Whether Micrometer instrumentation is registered. */
    @Valid
    private final Metrics metrics = new Metrics();

    /** Whether the REST layer is registered. */
    @Valid
    private final Web web = new Web();

    /** Enrichment defaults. A definition's stage overrides any of these for itself. */
    @Getter
    @Setter
    public static class Enrichment {

        /** Keys per call for a batched stage. Sized to fit a partner's usual request ceiling. */
        @Min(1)
        private int batchSize = 200;

        /**
         * Concurrent in-flight calls per stage within one window.
         *
         * <p>Four rather than "as many as the pool allows" because the bound exists to protect the
         * partner, not this service: a report is a burst of traffic a partner did not ask for, and
         * a fan-out matched to the local pool size would simply move the queue to their side.
         */
        @Min(1)
        private int concurrency = 4;

        /** Whether resolved values are cached for the life of a run. */
        private boolean cacheEnabled = true;

        /**
         * Cache entries per run, across all stages.
         *
         * <p>Fifty thousand entries of a small projection is a few megabytes and, at the design
         * point, the difference between a few thousand partner calls and a few hundred thousand.
         * Treat it as load-bearing rather than as an optimisation.
         */
        @Min(1)
        private int cacheSize = 50_000;

        /** Entry ceiling for a dimension stage's catalogue, above which the run fails. */
        @Min(1)
        private int maxDimensionEntries = 100_000;

        /**
         * Whose credentials a stage's partner calls carry, unless the stage says otherwise.
         *
         * <p>{@code SERVICE_ACCOUNT} by default, because it is the only identity available to a run
         * that is not on a request thread - a deferred run, a subscription, a reclaimed attempt - and a
         * default of {@code REQUESTER} would make every report that grew past
         * {@code sync-threshold-rows} stop working in production and nowhere else.
         *
         * <p>An estate whose partners all scope by the caller sets this to {@code REQUESTER} once here
         * rather than on every stage. See {@link CallIdentity}.
         */
        @NotNull
        private CallIdentity callAs = CallIdentity.SERVICE_ACCOUNT;

        /** Default policy for a key the partner does not know. */
        @NotNull
        private MissingPolicy.Kind missing = MissingPolicy.Kind.PLACEHOLDER;

        /** Default policy for a partner that failed after its client exhausted its retries. */
        @NotNull
        private FailurePolicy failure = FailurePolicy.FAIL_REPORT;
    }

    /** Temp files: where they live, and how orphans are removed. */
    @Getter
    @Setter
    public static class Temp {

        /**
         * Directory the engine writes partial files into. Empty means the JVM's temp directory.
         *
         * <p>Worth setting explicitly on any container: the JVM default is usually a small
         * overlay filesystem, and a four-hundred-megabyte workbook written into it fills the
         * container's writable layer rather than a volume sized for it.
         */
        private String directory;

        /** How old an orphaned temp file must be before the startup sweep removes it. */
        @NotNull
        private Duration orphanAge = Duration.ofHours(6);

        /** Whether the startup sweep runs at all. */
        private boolean sweepOnStartup = true;
    }

    /** Which formats exist for this estate, and their settings. */
    @Getter
    @Setter
    public static class Formats {

        /**
         * Format ids a requester may ask for, regardless of what a definition allows.
         *
         * <p>Empty means every registered format. This is the estate-level switch - an organisation
         * that has decided reports leave as CSV only sets it here once, rather than editing every
         * definition in every service.
         */
        private Set<String> enabled = new LinkedHashSet<>();

        /** XLSX writer settings. */
        @Valid
        private final Xlsx xlsx = new Xlsx();

        /** CSV writer settings. */
        @Valid
        private final Csv csv = new Csv();
    }

    /** XLSX writer settings. */
    @Getter
    @Setter
    public static class Xlsx {

        /**
         * Rows kept in memory per sheet before POI flushes them to its own temp file.
         *
         * <p>The streaming workbook's whole memory bound. Five hundred rows of twenty-five columns
         * is small; raising it buys throughput and costs heap linearly, and it is the first number
         * to look at if a run is fast but tight rather than slow.
         */
        @Min(1)
        private int flushWindow = 500;

        /**
         * Directory holding template workbooks, watched for changes when hot-reload is present.
         *
         * <p>Empty means templates are loaded from the classpath only. A watched directory is what
         * lets a branding change reach production without a deploy, which is the whole reason
         * template mode exists.
         */
        private String templateDirectory;

        /** Whether a freezing header row and an autofilter are applied to every data sheet. */
        private boolean freezeHeader = true;

        /** Whether the provenance sheet is written. Off only for a file meant for a machine. */
        private boolean metadataSheet = true;
    }

    /** CSV writer settings. */
    @Getter
    @Setter
    public static class Csv {

        /**
         * The default profile, {@code excel} or {@code rfc4180}.
         *
         * <p>Two profiles because the two audiences want opposite files and neither is wrong.
         * {@code excel} is UTF-8 with a byte-order mark, a delimiter taken from the locale's list
         * separator and CRLF line endings, which is what makes a Russian or German user's Excel open
         * the file with the columns separated; {@code rfc4180} is UTF-8 with no mark, commas and
         * strict quoting, which is what a parser on the other end expects. Defaulting to
         * {@code excel} because a human opening the file is the common case for a report, and a
         * machine consumer is in a position to ask for the other.
         */
        @NotBlank
        private String profile = "excel";

        /** Whether a request may override the profile and its delimiter, charset and mark. */
        private boolean allowRequestOverride = true;
    }

    /** Where finished files go, and how long they stay there. */
    @Getter
    @Setter
    public static class Sink {

        /**
         * The sink bean to use.
         *
         * <p>{@code filesystem} and {@code s3} are the two this module ships; a service that
         * contributes its own {@code ReportSink} bean names it here by its bean name. The startup
         * validator refuses a value that matches no registered sink, so a typo is a failure to start
         * rather than a run that finishes with nowhere to put its file.
         */
        @NotBlank
        private String type = "filesystem";

        /** Root directory for the filesystem sink. */
        private String directory;

        /**
         * Bucket for the {@code s3} sink. Required when {@code type} is {@code s3}.
         *
         * <p>Named here rather than inherited from {@code ludwig.storage}, because the storage module
         * has no single bucket: it serves whatever location it is handed, and a service may well
         * write reports to one bucket and read partner drops from another. What is shared is the
         * client, not the destination.
         */
        private String bucket;

        /**
         * Optional key prefix within the bucket, so reports can share a bucket with something else.
         *
         * <p>Leading and trailing slashes are normalised away, so {@code reports}, {@code /reports}
         * and {@code reports/} all mean the same thing.
         */
        private String keyPrefix;

        /**
         * How long an output remains downloadable before the purge removes it.
         *
         * <p>Finite on purpose. A report is a copy of production data in a portable file, and a
         * store of them that never expires is a growing, unindexed, unmonitored copy of the
         * database - which is a materially worse disclosure risk than the reports themselves.
         */
        @NotNull
        private Duration retention = Duration.ofDays(7);

        /** How often the purge looks for outputs past their retention. */
        @NotNull
        private Duration purgeInterval = Duration.ofHours(1);

        /** Attempts to store a finished file before the run is failed. */
        @Min(1)
        private int storeAttempts = 3;

        /** How long a signed download link stays valid. */
        @NotNull
        private Duration downloadLinkTtl = Duration.ofMinutes(15);
    }

    /** The asynchronous run poller, built on job-core's claim and lease. */
    @Getter
    @Setter
    public static class Poller {

        /** Whether this instance claims and executes deferred runs. */
        private boolean enabled = true;

        /** How often the poller looks for claimable runs. */
        @NotNull
        private Duration interval = Duration.ofSeconds(5);

        /**
         * Runs claimed per poll.
         *
         * <p>One by default, unlike the outbox's batch claim, because a report run holds a thread
         * for minutes rather than milliseconds: claiming four would mean an instance took work it
         * could only start one piece of, and the rest would sit claimed and idle while another
         * instance had capacity.
         */
        @Min(1)
        private int claimBatchSize = 1;

        /** How many runs this instance executes at once. */
        @Min(1)
        private int concurrency = 2;

        /**
         * How long a claim is held before another instance may take the run.
         *
         * <p>Renewed by a heartbeat while the run is alive, so an instance that dies has its work
         * reclaimed after this elapses rather than leaving a row stuck in {@code RUNNING} forever.
         */
        @NotNull
        private Duration leaseDuration = Duration.ofMinutes(5);

        /** How often the lease is renewed. Must divide the lease with room for one missed renewal. */
        @NotNull
        private Duration heartbeatInterval = Duration.ofMinutes(1);

        /** How long shutdown waits for a run in flight before abandoning it to the lease. */
        @NotNull
        private Duration drainTimeout = Duration.ofSeconds(30);

        /** Attempts before a failed run stops being retried. */
        @Min(1)
        private int maxAttempts = 3;

        /** First retry delay; subsequent ones follow job-core's backoff curve. */
        @NotNull
        private Duration retryInitialDelay = Duration.ofSeconds(30);

        /** Ceiling for the retry delay. */
        @NotNull
        private Duration retryMaxDelay = Duration.ofMinutes(10);
    }

    /** Per-user limits. */
    @Getter
    @Setter
    public static class Quota {

        /** Runs one user may have PENDING or RUNNING at once. */
        @Positive
        private int concurrentRunsPerUser = 3;

        /** Runs one user may start in a rolling day. */
        @Positive
        private int dailyRunsPerUser = 100;
    }

    /** Whether this module applies its own changelog. */
    @Getter
    @Setter
    public static class Liquibase {

        /** Off for a service that assembles its own master changelog and includes this module's. */
        private boolean enabled = true;
    }

    /** Whether Micrometer instrumentation is registered. */
    @Getter
    @Setter
    public static class Metrics {

        /** Off leaves the no-op implementation in place; nothing else changes. */
        private boolean enabled = true;
    }

    /** Whether the REST layer is registered. */
    @Getter
    @Setter
    public static class Web {

        /** Off for a service that schedules reports and exposes no endpoints of its own. */
        private boolean enabled = true;

        /** Base path for this module's controllers. */
        @NotBlank
        private String basePath = "/api/v1/reports";
    }
}
