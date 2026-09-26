package ru.ludwigandreas.idempotency.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.ludwigandreas.idempotency.api.ClaimMode;

/**
 * Everything a deployment decides about work-dedup.
 *
 * <p>The one number here that is a <b>correctness</b> parameter rather than a tuning knob is
 * {@link #getTtl()}, and the README says so twice. It is the window in which a retry is recognised:
 * shortening it converts duplicates into double executions, not into disk savings.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ludwig.idempotency")
public class IdempotencyProperties {

    /** Whether the module is wired at all. */
    private boolean enabled = true;

    /** Which backend serves claims. */
    @NotNull
    private Backend backend = Backend.POSTGRES;

    /**
     * The default window a claim is recognised for.
     *
     * <p>24 hours because that is the conventional floor for an HTTP {@code Idempotency-Key} and it
     * covers a client's whole retry budget with room to spare. A consumer usually needs longer than its
     * topic's retention rather than shorter: a redelivery after an offset reset is still a redelivery,
     * and a claim that expired first will be acted on a second time. Set it per scope where the two
     * disagree - see {@link #getScopes()}.
     */
    @NotNull
    private Duration ttl = Duration.ofHours(DEFAULT_TTL_HOURS);

    /**
     * How long an in-progress standalone claim is good for without a renewal.
     *
     * <p>Long enough that a slow handler, a garbage-collection pause or a slow partner call cannot cost a
     * live request its claim; short enough that a pod killed mid-request costs one caller one short wait
     * rather than a whole TTL. The same trade-off {@code ludwig.job-core.lock.default-lease} makes, and
     * deliberately a smaller number: a request is a shorter unit of work than a scheduled job.
     */
    @NotNull
    private Duration lease = Duration.ofSeconds(DEFAULT_LEASE_SECONDS);

    /** Per-scope overrides of {@link #getTtl()} and {@link #getLease()}. */
    private final List<ScopeSettings> scopes = new ArrayList<>();

    /** The HTTP surface. */
    private final Http http = new Http();

    /** The consumer surface. */
    private final Kafka kafka = new Kafka();

    /** The retention purge. */
    private final Purge purge = new Purge();

    /** The Redis backend's settings, used only when {@link #getBackend()} is {@link Backend#REDIS}. */
    private final Redis redis = new Redis();

    /** Whether this module registers its own Liquibase runner for the claim table. */
    private final Liquibase liquibase = new Liquibase();

    /** Default TTL, in hours. */
    private static final int DEFAULT_TTL_HOURS = 24;

    /** Default lease, in seconds. */
    private static final int DEFAULT_LEASE_SECONDS = 60;

    /** Which implementation of the store is wired. */
    public enum Backend {

        /** The claim table and its conditional upsert. The only backend that can serve both claim modes. */
        POSTGRES,

        /**
         * Redis, for {@link ClaimMode#STANDALONE} only.
         *
         * <p>A Redis claim is not transactional with the database write it protects. Read
         * {@code RedisIdempotencyStore}'s class documentation before choosing this - the caveat is on the
         * class rather than only here because this is the line somebody edits, not the page they read.
         */
        REDIS
    }

    /**
     * A per-scope override.
     *
     * @see IdempotencyProperties#getScopes()
     */
    @Getter
    @Setter
    public static class ScopeSettings {

        /** The scope this applies to, matched exactly. */
        @NotBlank
        private String scope;

        /** The window for this scope, or null to use the global default. */
        private Duration ttl;

        /** The lease for this scope, or null to use the global default. */
        private Duration lease;
    }

    /** The HTTP filter. */
    @Getter
    @Setter
    public static class Http {

        /**
         * Whether the filter is registered.
         *
         * <p>Off by default. A filter that started deduplicating every matching endpoint the moment this
         * module appeared on a classpath would change the behaviour of live APIs as a side effect of a
         * dependency bump - including turning a 201 into a replayed 201 for callers who never asked for
         * it. Opting in is one line; discovering that an API's contract changed is not.
         */
        private boolean enabled = false;

        /**
         * Path patterns the filter applies to, as Ant patterns.
         *
         * <p>Empty means "only handlers annotated {@code @Idempotent}", which is the narrower and usually
         * better way in: a service that owns its controllers says so in the code that would break if the
         * path changed.
         */
        private final List<String> pathPatterns = new ArrayList<>();

        /**
         * Methods the filter applies to.
         *
         * <p>{@code POST} and {@code PATCH} by default, and deliberately not {@code PUT} or
         * {@code DELETE}: those are idempotent by RFC 9110 already, so a retry of one is safe without a
         * key, and a stored replay would hide a genuine state change from the second caller. Never
         * {@code GET} - there is no work to dedup and the response cache belongs to HTTP.
         */
        private final List<String> methods = new ArrayList<>(List.of("POST", "PATCH"));

        /** Whether a matching request with no key is refused rather than let through. */
        private boolean keyRequired = false;

        /**
         * Whether the scope is derived per endpoint rather than being one scope for the whole service.
         *
         * <p>On, and moving it off is a decision about the client rather than about this service: one
         * scope for everything means a client that generates one key per user action and calls two
         * endpoints with it gets the first endpoint's response from the second.
         */
        private boolean scopePerEndpoint = true;

        /** Whether the request is fingerprinted, so a recycled key is refused instead of answered. */
        private boolean fingerprintRequests = true;

        /**
         * The largest request body that is read in order to fingerprint it.
         *
         * <p>A request past this size is still claimed and still deduplicated; what it loses is the
         * fingerprint check, and the filter logs that it did. Buffering an unbounded body in memory is
         * the failure mode {@code file-ingest} exists to avoid, and an endpoint whose bodies are that
         * large is not one this filter should be reading.
         */
        @Min(1)
        private int maxFingerprintedBody = DEFAULT_MAX_BODY;

        /**
         * The largest response that is stored for replay.
         *
         * <p>Past it, the claim is completed with nothing to replay: a later duplicate is told the work
         * was done rather than handed a truncated body. A stored response is written on every protected
         * request, so this is also the number that decides how big the claim table gets.
         */
        @Min(1)
        private int maxStoredResponse = DEFAULT_MAX_BODY;

        /**
         * Response headers worth replaying, by name, case-insensitively.
         *
         * <p>An allow-list rather than "everything except": replaying every header means replaying
         * {@code Date}, whatever a proxy added, and - at worst - {@code Set-Cookie}, which would hand a
         * second caller the first caller's session. {@code Location} and {@code ETag} are on it because
         * they are the two a client actually needs from a replayed create.
         */
        private final List<String> replayedHeaders =
                new ArrayList<>(List.of("Location", "ETag", "Content-Language"));

        /**
         * Statuses whose responses are stored for replay.
         *
         * <p>2xx only by default, expressed as a range. A stored 4xx would replay a client's own mistake
         * back at it forever, including after the mistake was fixed - the retry of a request that was
         * rejected for a bad field should be allowed to succeed. A stored 5xx would be worse: it would
         * make a transient failure permanent for that key.
         */
        @Min(1)
        private int minStoredStatus = DEFAULT_MIN_STORED_STATUS;

        /** The highest status whose response is stored. */
        @Min(1)
        private int maxStoredStatus = DEFAULT_MAX_STORED_STATUS;

        /** The filter's order in the chain. */
        private int order = DEFAULT_FILTER_ORDER;

        /** 256 KiB: large enough for any JSON request or response an API should be exchanging. */
        private static final int DEFAULT_MAX_BODY = 256 * 1024;

        private static final int DEFAULT_MIN_STORED_STATUS = 200;

        private static final int DEFAULT_MAX_STORED_STATUS = 299;

        /**
         * After security and correlation, before the handler.
         *
         * <p>It must run after authentication, because the audit event for a fingerprint mismatch names
         * the caller, and after the correlation filter, because the log line that explains a 409 is
         * useless without a correlation id. It must run before the handler, which is the whole point.
         */
        private static final int DEFAULT_FILTER_ORDER = 0;
    }

    /** The consumer-side record filter. */
    @Getter
    @Setter
    public static class Kafka {

        /**
         * Whether a {@code RecordFilterStrategy} bean is registered.
         *
         * <p>Off by default, and registering it is not enough on its own: a filter strategy only
         * deduplicates the listener containers whose factory was given it. That is deliberate - a bean
         * that silently attached itself to every listener in a service would dedup the ones that must not
         * be, such as a projection that is already convergent and correct on a replay.
         */
        private boolean enabled = false;

        /**
         * The claim mode a consumer claims in.
         *
         * <p>{@link ClaimMode#TRANSACTIONAL}, because the case this was written for is a listener whose
         * whole unit of work is one transaction, and a claim that commits independently of that work
         * turns a rollback into a message that will never be delivered. It requires the listener
         * container to be configured with a transaction manager; the filter says so loudly if it is not,
         * rather than degrading.
         */
        @NotNull
        private ClaimMode mode = ClaimMode.TRANSACTIONAL;

        /**
         * A record header carrying the dedup key, or null to use the record's own coordinates.
         *
         * <p>The coordinates - topic, partition, offset - identify a delivery rather than a message, and
         * that is the right default for at-least-once redelivery of the same record. It is the wrong
         * choice when the producer republishes the same logical event to a new offset, which is what a
         * header is for.
         */
        private String keyHeader;

        /** Whether the record's value is fingerprinted, so a reused key with a changed payload is caught. */
        private boolean fingerprintRecords = true;
    }

    /** The retention purge. */
    @Getter
    @Setter
    public static class Purge {

        /** Whether this instance schedules the purge. Every instance may; the lock decides which runs it. */
        private boolean enabled = true;

        /** How long after the previous run finishes the next one starts. */
        @NotNull
        private Duration interval = Duration.ofMinutes(DEFAULT_INTERVAL_MINUTES);

        /** How long after startup the first run happens, so a fresh pod is not purging while it warms up. */
        @NotNull
        private Duration initialDelay = Duration.ofMinutes(DEFAULT_INITIAL_DELAY_MINUTES);

        /**
         * How many claims one statement deletes.
         *
         * <p>Bounded so that the first run after a TTL is shortened is a series of short transactions
         * rather than one long one - a long delete would pin the oldest transaction id, stop autovacuum on
         * a table receiving an insert per request, and hold locks across the busiest path in the service.
         */
        // 100_000 as a literal rather than a constant: an annotation value must be a compile-time
        // constant declared before it, and a ceiling on a batch size reads better next to the batch size
        // than fifty lines below it.
        @Min(1)
        @Max(100_000)
        private int batchSize = DEFAULT_BATCH_SIZE;

        /** The most batches one run does before leaving the rest to the next tick. */
        @Min(1)
        private int maxBatchesPerRun = DEFAULT_MAX_BATCHES;

        /** The name the purge takes {@code job-core}'s lock under. */
        @NotBlank
        private String lockName = "ludwig-idempotency-purge";

        private static final int DEFAULT_INTERVAL_MINUTES = 15;

        private static final int DEFAULT_INITIAL_DELAY_MINUTES = 5;

        private static final int DEFAULT_BATCH_SIZE = 1000;

        private static final int DEFAULT_MAX_BATCHES = 100;
    }

    /** The Redis backend. */
    @Getter
    @Setter
    public static class Redis {

        /**
         * Namespace for every key the Redis store writes.
         *
         * <p>Namespaced so that a Redis shared with a cache cannot have that cache's eviction policy
         * quietly delete claims - which would be a silent loss of dedup, visible only as duplicate work.
         */
        @NotBlank
        private String keyPrefix = "ludwig:idempotency";
    }

    /** This module's own schema migration. */
    @Getter
    @Setter
    public static class Liquibase {

        /** Whether this module applies its own changelog. */
        private boolean enabled = true;
    }

    /**
     * The window for a scope, falling back to the global default.
     *
     * @param scope the scope
     * @return the TTL
     */
    public Duration ttlFor(String scope) {
        return settingsFor(scope).map(ScopeSettings::getTtl).orElse(ttl);
    }

    /**
     * The lease for a scope, falling back to the global default.
     *
     * @param scope the scope
     * @return the lease
     */
    public Duration leaseFor(String scope) {
        return settingsFor(scope).map(ScopeSettings::getLease).orElse(lease);
    }

    private Optional<ScopeSettings> settingsFor(String scope) {
        return scopes.stream().filter(settings -> settings.getScope().equals(scope)).findFirst();
    }
}
