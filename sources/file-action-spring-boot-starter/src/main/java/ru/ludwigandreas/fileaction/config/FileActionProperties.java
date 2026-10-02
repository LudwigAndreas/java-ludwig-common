package ru.ludwigandreas.fileaction.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.unit.DataSize;
import ru.ludwigandreas.fileaction.api.ActionMode;
import ru.ludwigandreas.fileaction.api.CommitPolicy;
import ru.ludwigandreas.fileaction.api.ErrorReportFormat;
import ru.ludwigandreas.fileaction.api.ExecutionMode;
import ru.ludwigandreas.fileaction.api.ScanningMode;
import ru.ludwigandreas.fileaction.format.SourceFormat;

/**
 * Everything about a file action that is not code.
 *
 * <p>The split is {@code file-ingest}'s and {@code reconciliation}'s: the size budget, the execution mode, the
 * commit policy, the confirm window and the report format are identical in shape across every action in every
 * service, so they live here; the binding and the handler genuinely differ, so they are Java.
 *
 * <p>Nothing in this class validates cross-field consistency. That is
 * {@code FileActionConfigurationValidator}'s job, because the interesting checks need the handler beans too -
 * a commit policy is only wrong once you know whether the handler is a document handler - and a validator that
 * ran before the beans existed could not make them.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "ludwig.file-action")
public class FileActionProperties {

    /** Whether the module is active at all. */
    private boolean enabled = true;

    /** Scanning policy, which fails closed. */
    @Valid
    private Scanning scanning = new Scanning();

    /** Where submitted bytes and generated artifacts live. */
    @Valid
    private Storage storage = new Storage();

    /** The ceiling an inline action may not exceed. */
    @Valid
    private Inline inline = new Inline();

    /** How the worker that processes deferred submissions behaves. */
    @Valid
    private Deferred deferred = new Deferred();

    /** What an action gets when it does not say. */
    @Valid
    private ActionDefaults defaults = new ActionDefaults();

    /**
     * The configured actions, by name.
     *
     * <p>A {@code LinkedHashMap} so that a startup failure lists them in the order the deployment wrote them,
     * which is the order whoever is reading the message is looking at.
     */
    @Valid
    private Map<String, Action> actions = new LinkedHashMap<>();

    /** Scanning policy. */
    @Getter
    @Setter
    public static class Scanning {

        /**
         * Whether a scanner is required, optional or off.
         *
         * <p>{@code REQUIRED} by default. A deployment with no {@code FileScanner} bean then fails to start,
         * and the message names this property - so having no scanning is a decision somebody took rather than
         * a gap nobody noticed.
         */
        @NotNull
        private ScanningMode mode = ScanningMode.REQUIRED;
    }

    /** Where bytes live. */
    @Getter
    @Setter
    public static class Storage {

        /**
         * The object-store prefix submitted files are written under.
         *
         * <p>No default. A default would have to be a local directory, which works on a laptop and silently
         * writes a production deployment's uploads to a pod's ephemeral disk.
         */
        private String uploads;

        /** The prefix generated artifacts - bound rows, reject reports - are written under. */
        private String artifacts;

        /**
         * How long a terminal submission's stored bytes are kept.
         *
         * <p>Seven days by default: long enough to investigate a dispute, short enough that a bucket of user
         * uploads is not an indefinite liability.
         */
        @NotNull
        private Duration retention = Duration.ofDays(7);

        /** How many expired submissions one retention pass collects. */
        @Positive
        private int retentionBatchSize = 100;

        /** How often the retention job runs. */
        private Duration retentionInterval = Duration.ofHours(1);

        /** How long the retention job holds the platform's distributed lock. */
        private Duration retentionLease = Duration.ofMinutes(5);
    }

    /** The deferred worker's tuning. */
    @Getter
    @Setter
    public static class Deferred {

        /**
         * How long a claim lasts before another instance may take the submission.
         *
         * <p>Must comfortably exceed the time one submission takes to process. Too short and two instances
         * process the same file; too long and a pod that died leaves its submission stalled for that long. Five
         * minutes suits the module's declared size ceiling.
         */
        private Duration lease = Duration.ofMinutes(5);

        /** How many submissions one tick claims. */
        @Positive
        private int batchSize = 5;

        /**
         * How many times a submission is attempted before it is left in {@code REJECTED}.
         *
         * <p>Three, matching the repository's own three-attempts rule. A submission failing the same way a
         * fourth time is failing for a reason a retry will not change, and a worker that kept trying would hide
         * it behind a log line per tick.
         */
        @Positive
        private int maxAttempts = 3;

        /** How often the worker looks for work. */
        private Duration interval = Duration.ofSeconds(10);

        /** How long after startup the worker first runs, so that a restarting pod is not immediately busy. */
        private Duration initialDelay = Duration.ofSeconds(15);

        /** How long a shutdown waits for an in-flight submission to finish. */
        private Duration drainTimeout = Duration.ofSeconds(30);
    }

    /** The inline ceiling. */
    @Getter
    @Setter
    public static class Inline {

        /**
         * The most rows an {@code INLINE} action may be configured for.
         *
         * <p>An action declaring {@code INLINE} and a larger {@code max-rows} does not start. Five thousand is
         * chosen as the point beyond which a request a person is waiting for stops being one - it is a
         * ceiling on configuration, not on files, so a deployment that genuinely wants more raises it
         * deliberately and sees the number it chose.
         */
        @Positive
        private int maxRows = 5_000;
    }

    /** What an action inherits when it does not say. */
    @Getter
    @Setter
    public static class ActionDefaults {

        /** The largest submission accepted. */
        private DataSize maxSize = DataSize.ofMegabytes(25);

        /**
         * The most data rows read.
         *
         * <p>Five thousand, which is {@link Inline#maxRows}, and the two numbers agreeing is not a coincidence:
         * {@link Action#execution} defaults to {@code INLINE}, so any other default here would make every action
         * fail to start until the deployment overrode something. It did - this was 100,000 against an inline
         * ceiling of 5,000, so {@code FileActionAutoConfigurationTest} could not start a context with a single
         * action in it. A starter whose own defaults contradict each other is not configurable, it is broken.
         *
         * <p>A {@code DEFERRED} action raises this, toward {@link #maxRowsLimit}.
         */
        @Positive
        private int maxRows = 5_000;

        /**
         * The most rows any action may be configured for, whatever its execution mode.
         *
         * <p>The module's declared size ceiling, and the number its heap budget is measured against by
         * {@code LargeWorkbookHeapIT}. An action configured above it does not start, and the refusal names
         * {@code file-ingest-spring-boot-starter} - because a file that large is a scheduled bulk import rather
         * than something a person is waiting for.
         */
        @Positive
        private int maxRowsLimit = 100_000;

        /** The formats accepted. */
        private List<SourceFormat> formats = List.of(SourceFormat.XLSX, SourceFormat.CSV);

        /** How many rows one transaction covers under {@code PER_BATCH} and {@code PER_ROW}. */
        private int batchSize = 500;

        /** How many rejects are stored for the paged endpoint. */
        @PositiveOrZero
        private int rejectSample = 100;

        /**
         * The fraction of rejected rows above which the whole submission is refused.
         *
         * <p>The "this is the wrong file entirely" guard. Applying the sixty per cent that happened to parse is
         * never what anybody wanted, and a user who uploaded last year's template would rather be told.
         */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double rejectThreshold = 0.1d;

        /** How long a validated submission stays confirmable. */
        private Duration confirmTtl = Duration.ofMinutes(30);

        /** The default report format. */
        private ErrorReportFormat errorReport = ErrorReportFormat.ANNOTATED_WORKBOOK;

        /** The archive ceilings, which apply to every workbook read. */
        private Archive archive = new Archive();
    }

    /** The ZIP container ceilings. */
    @Getter
    @Setter
    public static class Archive {

        /** The most entries a workbook container may hold. */
        private int maxEntries = 1_000;

        /**
         * The least compressed-to-uncompressed ratio tolerated per entry.
         *
         * <p>A legitimate workbook's XML compresses perhaps ten to one, so 0.01 leaves two orders of magnitude
         * of headroom; a bomb compresses a thousand to one.
         */
        private double minInflateRatio = 0.01d;

        /** The most a container may inflate to in total. */
        private DataSize maxUncompressed = DataSize.ofMegabytes(512);

        /** The most the shared-strings table may occupy, which POI materialises whatever the reader does. */
        private DataSize maxSharedStrings = DataSize.ofMegabytes(16);

        /** The most characters one cell may hold. */
        private int maxCellCharacters = 32_000;

        /** How long one read may take, for a file crafted to be slow rather than large. */
        private Duration readTimeout = Duration.ofMinutes(2);
    }

    /** One configured action. */
    @Getter
    @Setter
    public static class Action {

        /** How much of the lifecycle one submit performs. */
        private ActionMode mode = ActionMode.CONFIRM;

        /** Whether the caller waits. */
        private ExecutionMode execution = ExecutionMode.INLINE;

        /**
         * What happens to the other rows when one is refused.
         *
         * <p>Deliberately null by default rather than carrying a value. Every candidate default is silently
         * wrong for some domain and the symptom is applied business data, so
         * {@code FileActionConfigurationValidator} refuses to start an action that leaves this unset. See
         * {@link CommitPolicy}.
         */
        private CommitPolicy commitPolicy;

        /** Overrides the default, or null to inherit. */
        private DataSize maxSize;

        /** Overrides the default, or null to inherit. */
        private Integer maxRows;

        /** Overrides the default, or null to inherit. */
        private List<SourceFormat> formats;

        /** Overrides the default, or null to inherit. */
        private Integer batchSize;

        /** Overrides the default, or null to inherit. */
        private Integer rejectSample;

        /** Overrides the default, or null to inherit. */
        private Double rejectThreshold;

        /** Overrides the default, or null to inherit. */
        private Duration confirmTtl;

        /** Overrides the default, or null to inherit. */
        private ErrorReportFormat errorReport;

        /**
         * The authority a caller must hold to submit to this action.
         *
         * <p>Checked through {@code security-spring-boot-starter}, which is an optional dependency - so an
         * action naming an authority while that starter is absent does not start, rather than serving the
         * endpoint unprotected.
         */
        private String requiredAuthority;

        /** Overrides the default archive ceilings, or null to inherit. */
        private Archive archive;
    }
}
