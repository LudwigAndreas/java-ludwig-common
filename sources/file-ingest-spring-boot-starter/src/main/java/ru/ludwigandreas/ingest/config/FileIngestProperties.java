package ru.ludwigandreas.ingest.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.unit.DataSize;

/**
 * Everything an ingest is told, which is everything except the parser and the applier.
 *
 * <p>The division is the one {@code reconciliation-spring-boot-starter} uses: what is identical in
 * shape across every ingest in every service is configuration, and what genuinely differs is Java. A
 * task is one {@code @IngestTask} bean and one block below.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "ludwig.ingest")
public class FileIngestProperties {

    /** Whether the module wires anything at all. */
    private boolean enabled = true;

    /** Whether this instance runs scheduled ingests, as opposed to only serving the endpoint. */
    private boolean schedulerEnabled = true;

    /**
     * How long shutdown waits for an in-flight run to reach a checkpoint before abandoning it.
     *
     * <p>Abandoning is safe - the run resumes from its last committed checkpoint - so this is a
     * politeness budget rather than a correctness one. It is worth more than a second or two because
     * the work between checkpoints is thrown away, and one batch of five thousand records is cheap to
     * redo but not free.
     */
    @NotNull
    private Duration drainTimeout = Duration.ofSeconds(30);

    /** The Liquibase half; see {@code FileIngestLiquibaseAutoConfiguration}. */
    private final Liquibase liquibase = new Liquibase();

    /** The actuator endpoint's own settings. */
    private final Endpoint endpoint = new Endpoint();

    /** Whether Micrometer metrics are recorded, when Micrometer is present. */
    private final Metrics metrics = new Metrics();

    /** Whether completion is published through the outbox, when the outbox is present. */
    private final Events events = new Events();

    /** The tasks, by name. Each name must match an {@code @IngestTask} bean. */
    @Valid
    private final Map<String, Task> tasks = new LinkedHashMap<>();

    /** Whether this module applies its own changelog. */
    @Getter
    @Setter
    public static class Liquibase {

        /**
         * Whether to register this module's changelog as its own {@code SpringLiquibase}.
         *
         * <p>Off for a service that would rather fold the changelog into its own master history, as
         * {@code notification-service} does for the outbox and identity schemas. On by default,
         * because a starter that silently created no tables would fail at the first run rather than
         * at startup.
         */
        private boolean enabled = true;
    }

    /** The actuator endpoint. */
    @Getter
    @Setter
    public static class Endpoint {

        /** Whether the {@code fileingest} endpoint bean is created at all. */
        private boolean enabled = true;

        /** How many recent runs a listing shows per task. */
        @Min(1)
        private int recentRuns = 20;

        /**
         * Whether the endpoint may trigger a run outside the schedule.
         *
         * <p>Off by default. A manual run is not destructive - the identity constraint still refuses
         * an object already ingested - but it does put load on the database at a time nobody planned
         * for, and an endpoint that can start work is a different security proposition from one that
         * only reads.
         */
        private boolean allowManualRun;
    }

    /** Metrics. */
    @Getter
    @Setter
    public static class Metrics {

        /** Whether the Micrometer implementation replaces the no-op. */
        private boolean enabled = true;
    }

    /** Completion events. */
    @Getter
    @Setter
    public static class Events {

        /**
         * Whether a completed run publishes {@code ingest.completed} through the outbox.
         *
         * <p>Off by default, because publishing an event nobody consumes fills a table. Turning it on
         * requires {@code outbox-spring-boot-starter} on the classpath; the startup validator says so
         * rather than leaving it silently inert.
         */
        private boolean enabled;
    }

    /** One ingest. */
    @Getter
    @Setter
    public static class Task {

        /** Whether this task is scheduled. A task switched off keeps its configuration and its runs. */
        private boolean enabled = true;

        /** Where the objects are. */
        @Valid
        @NotNull
        private final Source source = new Source();

        /** How to know the object is complete before reading it. */
        @Valid
        @NotNull
        private final Arrival arrival = new Arrival();

        /** When to look. */
        @Valid
        @NotNull
        private final Schedule schedule = new Schedule();

        /** The run lock. */
        @Valid
        @NotNull
        private final Lock lock = new Lock();

        /** How much is held in memory at once. */
        @Valid
        @NotNull
        private final Batch batch = new Batch();

        /** What happens to a record that cannot be processed. */
        @Valid
        @NotNull
        private final Quarantine quarantine = new Quarantine();

        /** The receipt object written after a successful run. */
        @Valid
        @NotNull
        private final Receipt receipt = new Receipt();

        /** What happens to the source object afterwards. */
        @Valid
        @NotNull
        private final Archive archive = new Archive();

        /** When the absence of a file becomes a signal. */
        @Valid
        @NotNull
        private final Alert alert = new Alert();

        /** How records get from staging into the target. */
        @Valid
        @NotNull
        private final Write write = new Write();
    }

    /** Where the objects are. */
    @Getter
    @Setter
    public static class Source {

        /**
         * The prefix to look under, for example {@code s3://partner-drop/catalogue/}.
         *
         * <p>A location in the platform's own scheme, so the same value points at a bucket in a
         * deployment and at a directory in a developer's checkout without being edited - see
         * {@code FilesystemObjectStore}.
         */
        @NotBlank
        private String uri;

        /**
         * A glob matched against the object's name, for example {@code catalogue-*.csv.gz}.
         *
         * <p>Matched against the name rather than the whole key, so that a prefix holding a year of
         * daily drops does not need the pattern to know the date layout.
         */
        @NotBlank
        private String pattern = "*";

        /**
         * Whether to decompress the stream before parsing.
         *
         * <p>{@code auto} decides from the object's name: {@code .gz} is gzipped, anything else is
         * not. Explicit values exist for a partner whose file is gzipped and not named so, which is
         * common enough to be worth a property rather than a wrapper class.
         */
        @NotNull
        private Compression compression = Compression.AUTO;

        /**
         * How many candidate objects one pass may ingest.
         *
         * <p>One, by default, and deliberately: a once-a-day drop produces one file, and a pass that
         * found five would be a backlog somebody should look at rather than work to chew through
         * silently at six-thirty in the morning.
         */
        @Min(1)
        private int maxObjectsPerPass = 1;
    }

    /** Whether and how the stream is decompressed. */
    public enum Compression {

        /** Decide from the object's name. */
        AUTO,

        /** Always gzip. */
        GZIP,

        /** Never. */
        NONE
    }

    /** How to know the object is complete. */
    @Getter
    @Setter
    public static class Arrival {

        /**
         * The name of a sentinel object that must exist before the data object is read.
         *
         * <p>{@code {name}} is replaced by the data object's own name, so {@code "{name}.done"} means
         * {@code data.csv} is not touched until {@code data.csv.done} exists. On by default, because
         * reading a half-written object is the most common way a daily ingest loses data and it
         * produces a <em>successful</em> run with a truncated tail, which nothing alerts on.
         *
         * <p>Set to empty to switch sentinel detection off, for a partner that publishes none - and
         * then set {@link #stabilityWindow} instead.
         */
        private String sentinel = "{name}.done";

        /**
         * How long an object's size and etag must be unchanged before it counts as complete.
         *
         * <p>The fallback when there is no sentinel: the object is looked at twice, this far apart,
         * and read only if nothing changed. Weaker than a sentinel - a partner whose upload stalls for
         * longer than the window looks complete - which is why the sentinel is the default and this is
         * what you use when there is nothing better.
         *
         * <p>Zero switches it off. With no sentinel and no stability window, the object is read as
         * soon as it is seen, which is a decision rather than an oversight only if the partner writes
         * atomically.
         */
        @NotNull
        private Duration stabilityWindow = Duration.ZERO;

        /**
         * A key inside the sentinel whose value is the number of records the data object should hold.
         *
         * <p>When present and the sentinel is JSON carrying it, the balance check additionally
         * requires {@code records_read == expected}. This is the strongest check the module has,
         * because it is the only one that can detect a file which is internally consistent and
         * short - a truncated upload that happened to end on a record boundary.
         */
        private String expectedCountField = "recordCount";
    }

    /** When to look. */
    @Getter
    @Setter
    public static class Schedule {

        /**
         * A Spring cron expression, for a once-a-day job.
         *
         * <p>Six fields, seconds first. A daily drop expected by seven is usually looked for at half
         * past six, which is {@code "0 30 6 * * *"}.
         */
        @NotBlank
        private String cron = "0 30 6 * * *";

        /**
         * How long a run may take before it is abandoned.
         *
         * <p>Abandoning is safe: the checkpoint means the next pass resumes. A run that has genuinely
         * hung - a stream that never ends, a partner store that accepts a connection and sends nothing
         * - is otherwise held only by the socket timeout, which is per-read rather than per-run.
         */
        @NotNull
        private Duration runTimeout = Duration.ofHours(2);
    }

    /** The run lock. */
    @Getter
    @Setter
    public static class Lock {

        /**
         * How long the lock is held for before it must be renewed.
         *
         * <p>Short on purpose: it is how long a dead instance blocks the task, and the run renews it
         * inside the batch loop, so a short lease costs nothing but a few extra statements. A long
         * lease is not safer - it just delays the recovery.
         */
        @NotNull
        private Duration lease = Duration.ofMinutes(5);

        /**
         * How much of the lease must remain before a renewal is attempted.
         *
         * <p>A fraction rather than a duration, so that changing the lease does not silently change
         * the safety margin. At the default, a five-minute lease is renewed when ninety seconds are
         * left - enough for a slow statement to finish and the renewal to be retried once before the
         * lease would actually lapse.
         */
        @DecimalMin("0.05")
        @DecimalMax("0.9")
        private double renewAtRemainingFraction = 0.3;
    }

    /** How much is held in memory at once. */
    @Getter
    @Setter
    public static class Batch {

        /**
         * Records per batch.
         *
         * <p>One of two bounds, and on its own the wrong one. A record-count bound is a module that
         * works until a partner sends one row with a 200 MB free-text column - see {@link #maxBytes}.
         */
        @Min(1)
        private int maxRecords = 5000;

        /**
         * Bytes per batch, whichever limit is reached first.
         *
         * <p>The bound that actually holds the heap, because memory is consumed in bytes and not in
         * records. A single record larger than this is a poison record: it is quarantined with its
         * offset rather than grown into, because growing is an {@code OutOfMemoryError} that names
         * none of four million rows.
         */
        @NotNull
        private DataSize maxBytes = DataSize.ofMegabytes(32);
    }

    /** What happens to a record that cannot be processed. */
    @Getter
    @Setter
    public static class Quarantine {

        /** Whether the first bad record fails the run, or a rate does. */
        @NotNull
        private QuarantinePolicy policy = QuarantinePolicy.THRESHOLD;

        /**
         * The share of records that may be quarantined before the run fails, under
         * {@link QuarantinePolicy#THRESHOLD}.
         *
         * <p>Small, because the thing this catches is not bad data but a changed file format, and that
         * takes the rate from a handful of rows to most of the file. A generous ratio would let a
         * shifted column through.
         */
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double maxRatio = 0.005;

        /**
         * How much of a bad record's own text a quarantine row keeps.
         *
         * <p>Capped because the record that failed is disproportionately likely to be the enormous
         * one, and a quarantine table is not where a 200 MB column should end up.
         */
        @NotNull
        private DataSize maxRecordLength = DataSize.ofKilobytes(8);
    }

    /** Whether the first bad record fails the run, or a rate does. */
    public enum QuarantinePolicy {

        /**
         * The first record that cannot be processed fails the run.
         *
         * <p>Right for a file that is entirely wrong, where dying on row one is the fastest possible
         * signal. Wrong for one bad row in four million, which is why it is not the default.
         */
        FAIL_FAST,

        /**
         * Continue while the quarantine rate stays under the limit, fail past it.
         *
         * <p>The default, and the only policy that catches a shifted column or a changed delimiter -
         * a file where every row parses and every row is wrong.
         */
        THRESHOLD
    }

    /** The receipt object written after a successful run. */
    @Getter
    @Setter
    public static class Receipt {

        /**
         * Whether to write one.
         *
         * <p>Off by default. "Send a done file" covers two separate features and this is the outbound
         * half; a partner who does not read receipts should not have objects written into their bucket.
         */
        private boolean enabled;

        /**
         * Where it goes, with {@code {name}} replaced by the source object's name.
         *
         * <p>Relative to the source's container, so a receipt lands in the same bucket unless the
         * template names another location outright.
         */
        @NotBlank
        private String keyTemplate = "processed/{name}.receipt.json";
    }

    /** What happens to the source object after a successful run. */
    @Getter
    @Setter
    public static class Archive {

        /** Whether to move it, copy it, tag it, or leave it. */
        @NotNull
        private ArchiveMode mode = ArchiveMode.NONE;

        /** The prefix an archived object is written under, for {@code move} and {@code copy}. */
        private String prefix = "processed/";
    }

    /** What happens to the source object after a successful run. */
    public enum ArchiveMode {

        /**
         * Leave it where it is.
         *
         * <p>The default, because the identity constraint already makes re-offering the same object a
         * no-op, so archival is about the partner's bucket hygiene rather than about correctness. An
         * estate whose partner cleans up after itself wants this.
         */
        NONE,

        /** Copy to the archive prefix and delete the original. */
        MOVE,

        /** Copy to the archive prefix and leave the original. */
        COPY
    }

    /** When the absence of a file becomes a signal. */
    @Getter
    @Setter
    public static class Alert {

        /**
         * The local time by which a file is expected.
         *
         * <p>Unset means no expectation and no missing-file signal. Setting it is what turns
         * {@code ludwig.ingest.missing} from a gauge that is always zero into the one metric that
         * catches the failure nobody notices: for a once-a-day job, the file that never showed up. The
         * schedule fires, finds nothing, logs at DEBUG, and the table quietly goes stale.
         */
        private LocalTime expectedBy;

        /** The zone {@link #expectedBy} is read in. */
        @NotBlank
        private String zone = "UTC";
    }

    /** How records get from staging into the target. */
    @Getter
    @Setter
    public static class Write {

        /** Which writer puts records into the staging table. */
        @NotNull
        private StagingWriterType stagingWriter = StagingWriterType.AUTO;

        /** Rows per JDBC batch, for the {@code jdbc-batch} writer. */
        @Min(1)
        private int jdbcBatchSize = 1000;

        /**
         * Whether the staging table is emptied before a run that starts from scratch.
         *
         * <p>On, and it must be: a run that starts at checkpoint zero is either the first attempt or
         * one whose previous attempt is being abandoned, and staged rows from that previous attempt
         * would be merged into the target alongside the new ones. A resumed run - one whose checkpoint
         * is not zero - deliberately does <em>not</em> truncate, because those staged rows are exactly
         * the work the checkpoint says has already been done.
         */
        private boolean truncateStagingOnFreshRun = true;
    }

    /** Which writer puts records into the staging table. */
    public enum StagingWriterType {

        /**
         * Postgres {@code COPY} where the connection is Postgres, JDBC batch otherwise.
         *
         * <p>The default, so that the module is fast where it can be and correct everywhere, without
         * a service having to know which it got.
         */
        AUTO,

        /**
         * Postgres {@code COPY} via {@code CopyManager}.
         *
         * <p>Several times faster than batched {@code INSERT} for the row counts this module is built
         * for, and the reason the SQL carve-out exists at all.
         */
        COPY,

        /**
         * Batched {@code INSERT} through JDBC.
         *
         * <p>Slower and portable, so the module is not accidentally Postgres-only.
         */
        JDBC_BATCH
    }
}
