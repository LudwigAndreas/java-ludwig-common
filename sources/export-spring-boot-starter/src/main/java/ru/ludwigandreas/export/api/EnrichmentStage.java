package ru.ludwigandreas.export.api;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * One join against another service, declared on the definition and executed per window.
 *
 * <pre>{@code
 * EnrichmentStage<OrderRow, UUID, CustomerView> CUSTOMER = EnrichmentStage
 *         .<OrderRow, UUID, CustomerView>of("customer", CUSTOMER_ENRICHER)
 *         .keyExtractor(OrderRow::customerId)
 *         .merge(OrderRow::withCustomer)
 *         .restClient("identity-provider")
 *         .missingPolicy(MissingPolicy.placeholder())
 *         .failurePolicy(FailurePolicy.FAIL_REPORT)
 *         .build();
 * }</pre>
 *
 * <h2>Why the merge is a function and not a setter</h2>
 *
 * <p>{@link #merge} returns the enriched row rather than mutating it, which lets the row type be an
 * immutable record and lets a window be enriched by several stages concurrently without any of them
 * seeing another's half-written state. A mutating variant would work today and would break the first
 * time two independent stages ran in parallel on the same window - which is exactly what the engine
 * does by default, and exactly the kind of failure that appears only under load.
 *
 * <h2>Ordering</h2>
 *
 * <p>Stages with no declared dependency are independent and run concurrently within a window. A
 * stage that needs a key only a previous stage can supply names it in {@link #dependsOn}, and the
 * engine then orders them. Declaring the dependency is required rather than inferred: inferring it
 * from which fields a key extractor happens to touch is not something the JVM can tell the engine,
 * and a stage that silently ran before its prerequisite would read a null key and report every row
 * as not found.
 *
 * @param <R> the row type being enriched
 * @param <K> the key this stage looks partner data up by
 * @param <V> the value the partner returns
 */
@Getter
@EqualsAndHashCode(of = "name")
public final class EnrichmentStage<R, K, V> {

    /**
     * Stage names are lowercase, dash- or dot-separated. Enforced rather than recommended because a
     * name is an identifier outside this process: it is a metric tag, it appears in the run record's
     * list of degraded stages, and it is written into the metadata sheet of every file the stage
     * touched. Two spellings of one stage would be two series in the metrics and one stage to
     * everybody reading them.
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9]*([.\\-][a-z0-9]+)*$");

    private static final int MAX_NAME_LENGTH = 64;

    /** Stable identifier, unique within a definition. Used in metrics, logs and the metadata sheet. */
    private final String name;

    /** How the engine gets this stage's key out of a row. Returning null means the row has no key. */
    private final Function<R, K> keyExtractor;

    /** The partner walk. See {@link Enricher} for the three shapes and what each costs. */
    private final Enricher<K, V> enricher;

    /** Produces the enriched row. Must not mutate its argument; see the class comment. */
    private final BiFunction<R, V, R> merge;

    /** What to do when the partner does not know a key. */
    private final MissingPolicy missingPolicy;

    /** What to do when the partner fails, after its REST client has exhausted its retries. */
    private final FailurePolicy failurePolicy;

    /**
     * The {@code @LudwigRestClient} name this stage's enricher calls through.
     *
     * <p>Declared here purely so the startup validator can check it: a stage whose concurrency
     * exceeds the named client's connection pool cannot reach that concurrency and will instead
     * queue inside the pool, turning a bounded fan-out into an unbounded wait. Naming a client that
     * does not exist fails the context rather than the first run.
     */
    private final String restClient;

    /**
     * Whose credentials this stage's partner calls carry, or null to inherit
     * {@code ludwig.export.enrichment.call-as}.
     *
     * <p>Per stage rather than per report, because one report legitimately joins both kinds of partner:
     * a reference catalogue this service integrates with under its own credentials, and a
     * customer-facing service whose own scoping must be respected. Forcing one identity for the whole
     * report would make the second of those inexpressible without splitting the report in two.
     *
     * <p>See {@link CallIdentity} for what each means and what the startup validator checks it against.
     */
    private final CallIdentity callAs;

    /** Stages that must have run before this one, by name. Empty means this stage is independent. */
    private final Set<String> dependsOn;

    /**
     * Keys per call for a {@link Enricher.Batched} stage; null inherits the module default.
     *
     * <p>Per-stage rather than global because the ceiling is the partner's, not the engine's: a
     * partner with a URL-based batch endpoint runs out of query string long before one with a POST
     * body does.
     */
    private final Integer batchSize;

    /** Concurrent in-flight calls for this stage within one window; null inherits the module default. */
    private final Integer concurrency;

    /**
     * Whether resolved values are cached for the life of the run.
     *
     * <p>On by default, and load-bearing. At the design point a key recurs across windows - a
     * thousand orders from one customer - and the difference between caching and not is roughly two
     * orders of magnitude in partner calls. It is switchable because a stage whose keys are unique
     * per row pays the cache's cost for no hits.
     */
    private final boolean cacheEnabled;

    /** Cache entry ceiling for this stage; null inherits the module default. */
    private final Integer cacheSize;

    /**
     * Entry ceiling for a {@link Enricher.Dimension} stage's catalogue; null inherits the default.
     *
     * <p>Exceeding it fails the run rather than truncating the catalogue, because a truncated
     * dimension produces a file in which an arbitrary subset of rows is marked not found.
     */
    private final Integer maxDimensionEntries;

    // SUPPRESS CHECKSTYLE ParameterNumber - the canonical constructor behind @Builder. The rule
    // exists to catch call sites nobody can read; nothing calls this positionally, because the
    // builder is the only way in.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Builder
    private EnrichmentStage(String name, Function<R, K> keyExtractor, Enricher<K, V> enricher,
                            BiFunction<R, V, R> merge, MissingPolicy missingPolicy,
                            FailurePolicy failurePolicy, String restClient, Set<String> dependsOn,
                            Integer batchSize, Integer concurrency, Boolean cacheEnabled,
                            Integer cacheSize, Integer maxDimensionEntries, CallIdentity callAs) {
        this.name = requireName(name);
        this.keyExtractor = require(keyExtractor, this.name, "a key extractor");
        this.enricher = require(enricher, this.name, "an enricher");
        this.merge = require(merge, this.name, "a merge function");
        this.missingPolicy = missingPolicy == null ? MissingPolicy.placeholder() : missingPolicy;
        this.failurePolicy = failurePolicy == null ? FailurePolicy.FAIL_REPORT : failurePolicy;
        this.restClient = restClient;
        this.dependsOn = dependsOn == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(dependsOn));
        this.batchSize = requireAtLeastOne(batchSize, this.name, "batch size");
        this.concurrency = requireAtLeastOne(concurrency, this.name, "concurrency");
        this.cacheEnabled = cacheEnabled == null || cacheEnabled;
        this.cacheSize = requireAtLeastOne(cacheSize, this.name, "cache size");
        this.maxDimensionEntries = requireAtLeastOne(maxDimensionEntries, this.name, "dimension ceiling");
        this.callAs = callAs;
    }

    private static String requireName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            throw new IllegalArgumentException("An enrichment stage needs a name");
        }
        if (candidate.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Enrichment stage name is longer than " + MAX_NAME_LENGTH + " characters: " + candidate);
        }
        if (!NAME_PATTERN.matcher(candidate).matches()) {
            throw new IllegalArgumentException(
                    "Enrichment stage name must be lowercase dot- or dash-separated segments, was: "
                            + candidate);
        }
        return candidate;
    }

    private static <T> T require(T value, String stageName, String what) {
        if (value == null) {
            throw new IllegalArgumentException("Enrichment stage " + stageName + " needs " + what);
        }
        return value;
    }

    private static Integer requireAtLeastOne(Integer value, String stageName, String what) {
        if (value != null && value < 1) {
            throw new IllegalArgumentException(
                    "Enrichment stage " + stageName + " has a " + what + " below 1: " + value);
        }
        return value;
    }

    /**
     * Entry point for the builder, so the two things a stage cannot omit are named up front.
     *
     * @param name     the stage's identifier within its definition
     * @param enricher the partner walk
     * @param <R>      the row type being enriched
     * @param <K>      the key type
     * @param <V>      the partner value type
     * @return a builder still needing a key extractor and a merge function
     */
    public static <R, K, V> EnrichmentStageBuilder<R, K, V> of(String name, Enricher<K, V> enricher) {
        return EnrichmentStage.<R, K, V>builder().name(name).enricher(enricher);
    }

    /** Never includes the enricher or the functions; this string ends up in startup logs. */
    @Override
    public String toString() {
        return "EnrichmentStage(" + name + ": " + shapeName() + ")";
    }

    /** Which of the three walks this stage's enricher is, for logs, metrics and validation messages. */
    public String shapeName() {
        if (enricher instanceof Enricher.Batched<K, V>) {
            return "batched";
        }
        if (enricher instanceof Enricher.PerItem<K, V>) {
            return "per-item";
        }
        return "dimension";
    }
}
