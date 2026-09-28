package ru.ludwigandreas.messaging.api;

/**
 * The wire header names the platform's producers write and its consumers read.
 *
 * <p>This class is the whole reason this module exists and the whole reason
 * {@code outbox-spring-boot-starter} depends on it. {@code OutboxEvent} has carried
 * {@code aggregateType}, {@code aggregateId}, {@code eventType}, {@code eventVersion},
 * {@code idempotencyKey} and {@code traceId} since it was written, and {@code KafkaOutboxDispatcher}
 * has been putting some of them on the record - but the names lived as string literals in the
 * dispatcher, so there was no way for a consumer to read them back other than by copying the literal.
 * One consumer did exactly that, with a constant and a comment explaining that the two have to agree;
 * the other two gave up and parsed the payload instead, which is why {@code eventVersion} reached
 * production with nothing on the platform consuming it.
 *
 * <p>Naming them here, in a module both halves depend on, is what makes a rename a compile error in
 * the other half instead of a silent no-op.
 *
 * <h2>Why the {@code ludwig-} prefix</h2>
 *
 * <p>A topic is shared infrastructure. A header called {@code event-type} is a name anything might
 * already be using - a partner system publishing to the same topic, a Kafka Connect sink, a schema
 * registry's own tooling - and a collision presents as a consumer reading somebody else's value with
 * no error anywhere. A namespaced prefix costs a few bytes per record and makes every one of these
 * names unambiguously this platform's.
 *
 * <h2>The one name that does not follow the prefix, and why</h2>
 *
 * <p>{@link #CORRELATION_ID} is {@code X-Correlation-Id}, not {@code ludwig-correlation-id}. It is
 * deliberately the name {@code observability-spring-boot-starter} already reads and writes on both
 * HTTP requests and Kafka records, and it is configurable there. A correlation id that travelled as
 * one name over HTTP and another over the broker would be two ids as far as every log query is
 * concerned - which is the two-homes problem this module was created to remove, not to add. The
 * header's owner is the observability module; it is named here so a consumer reading the envelope has
 * one place to look, and {@link EnvelopeReader} accepts a configured override for the deployment that
 * changed it.
 *
 * <h2>The legacy names, and when they can go</h2>
 *
 * <p>{@code KafkaOutboxDispatcher} wrote {@code event-type}, {@code event-version},
 * {@code idempotency-key} and {@code trace-id} before this class existed, and there are records on
 * real topics carrying them - a topic's retention outlives a release. So the dispatcher writes both
 * spellings and {@link EnvelopeReader} reads the canonical one first and falls back to the legacy one.
 *
 * <p>The legacy write can be dropped once every topic's retention window has passed with the new
 * producer deployed, and the legacy read once no consumer group can still be asked to replay from
 * before that point. Dropping either earlier means a consumer that reads {@code null} where a value
 * exists, which for {@link #EVENT_TYPE} means a record silently ignored as "unknown type".
 *
 * <p>The pairing of a canonical name with its legacy fallback is stated once, in
 * {@link EnvelopeReader}, rather than as a table here. A table would be a second place to keep in step
 * with the reader, which is the class of problem this file exists to remove.
 */
public final class MessageHeaders {

    /** The prefix every header this platform defines carries - see the class comment. */
    public static final String PREFIX = "ludwig-";

    /** The kind of event, e.g. {@code OrderCreated}. What a consumer dispatches on. */
    public static final String EVENT_TYPE = PREFIX + "event-type";

    /**
     * The payload's schema version, as a decimal integer.
     *
     * <p>Produced since {@code OutboxEvent} was written and consumed by nothing until this module. See
     * {@code VersionGatingDeserializer} for why reading it matters: a payload that evolves under a
     * consumer which ignores the version is deserialized into a type that no longer matches, field by
     * field, silently - which is a data-corruption path, not a compatibility inconvenience.
     */
    public static final String EVENT_VERSION = PREFIX + "event-version";

    /** The type of aggregate the event is about, e.g. {@code Order}. */
    public static final String AGGREGATE_TYPE = PREFIX + "aggregate-type";

    /** The id of the aggregate instance the event is about. */
    public static final String AGGREGATE_ID = PREFIX + "aggregate-id";

    /**
     * The producer's dedup key for this event.
     *
     * <p>This is what {@code IdempotentRecordFilterStrategy} is configured to claim under when a
     * deployment wants dedup across a republish rather than only across a redelivery: only the producer
     * knows that two records at two offsets are one event.
     */
    public static final String IDEMPOTENCY_KEY = PREFIX + "idempotency-key";

    /** The correlation id. Owned by {@code observability-spring-boot-starter} - see the class comment. */
    public static final String CORRELATION_ID = "X-Correlation-Id";

    /** The W3C trace id, when the producer's trace was sampled. */
    public static final String TRACE_ID = PREFIX + "trace-id";

    /**
     * When the producer emitted the record, ISO-8601 in UTC.
     *
     * <p>Not the broker's timestamp, which is what {@code ConsumerRecord#timestamp()} gives and which a
     * broker configured with {@code LogAppendTime} overwrites. The two differ by however long the event
     * sat in the outbox table waiting for the poller, and that difference is the only way a consumer can
     * tell "this event is old because the producer was backed up" from "this event is old because I am
     * behind".
     */
    public static final String PRODUCED_AT = PREFIX + "produced-at";

    /** Pre-{@link #PREFIX} spelling of {@link #EVENT_TYPE}. See the class comment. */
    public static final String LEGACY_EVENT_TYPE = "event-type";

    /** Pre-{@link #PREFIX} spelling of {@link #EVENT_VERSION}. */
    public static final String LEGACY_EVENT_VERSION = "event-version";

    /** Pre-{@link #PREFIX} spelling of {@link #IDEMPOTENCY_KEY}. */
    public static final String LEGACY_IDEMPOTENCY_KEY = "idempotency-key";

    /** Pre-{@link #PREFIX} spelling of {@link #TRACE_ID}. */
    public static final String LEGACY_TRACE_ID = "trace-id";

    private MessageHeaders() {
    }
}
