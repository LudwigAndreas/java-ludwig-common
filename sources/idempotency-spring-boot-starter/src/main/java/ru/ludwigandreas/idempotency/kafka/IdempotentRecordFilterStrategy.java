package ru.ludwigandreas.idempotency.kafka;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import ru.ludwigandreas.idempotency.api.ClaimRequest;
import ru.ludwigandreas.idempotency.api.ClaimResult;
import ru.ludwigandreas.idempotency.api.IdempotencyScopes;
import ru.ludwigandreas.idempotency.api.IdempotencyStore;
import ru.ludwigandreas.idempotency.api.RequestFingerprint;
import ru.ludwigandreas.idempotency.config.IdempotencyProperties;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;

/**
 * Consumer-side dedup: claims a record's key before the listener body runs, so that at-least-once
 * delivery stops meaning at-least-once <em>effect</em>.
 *
 * <p>This is the case the primitive was originally written for, and the one every consumer in the platform
 * would otherwise hand-wire. A Kafka consumer is at-least-once by construction: a rebalance, an offset
 * reset, a deploy, or a commit that failed after the work succeeded all redeliver records the listener has
 * already acted on, and "have I already done this?" is the normal path rather than an edge case.
 *
 * <h2>Why a {@code RecordFilterStrategy} and not an aspect</h2>
 *
 * <p>Two reasons, and the second is the important one.
 *
 * <p>An aspect would make this module drag {@code spring-boot-starter-aop} into every consumer, including
 * the ones that only want the store.
 *
 * <p>More to the point, a filter strategy runs <em>inside</em> the listener container's invocation, which
 * is inside the container's transaction when one is configured - and that is exactly where a
 * {@link ru.ludwigandreas.idempotency.api.ClaimMode#TRANSACTIONAL} claim has to happen. A claim that
 * committed outside the listener's transaction would leave the key reserved for work that then rolled
 * back, and the redelivery of that record - which Kafka will certainly perform, because the offset was
 * not committed either - would be discarded as a duplicate. The message would never be delivered and
 * nothing would say so.
 *
 * <h2>How it is wired, and why it does not wire itself</h2>
 *
 * <p>A filter strategy deduplicates the listener containers whose factory was given it, which means
 * adopting this is one line in the factory the service already owns:
 *
 * <pre>{@code
 * factory.setRecordFilterStrategy(idempotentRecordFilterStrategy);
 * factory.getContainerProperties().setTransactionManager(transactionManager);
 * }</pre>
 *
 * <p>Deliberately not attached to every listener automatically. Some listeners must <em>not</em> be
 * deduplicated: a projection that converges on a value - the identity projection's timestamp compare, the
 * user-settings replay - is already correct on a replay and needs no key, and switching dedup on in front
 * of it would add a table, a write and a failure mode for a guarantee it already had.
 *
 * <h2>What a filtered record means to the container</h2>
 *
 * <p>A filtered record is acknowledged and its offset committed, which is the right outcome: the work it
 * describes was done by whoever won the claim, so there is nothing left to do and nothing to retry. What
 * this must never be used for is filtering in front of a listener whose own retry logic depends on seeing
 * the record again.
 */
@Slf4j
public class IdempotentRecordFilterStrategy implements RecordFilterStrategy<Object, Object> {

    private final IdempotencyStore store;
    private final RequestFingerprint fingerprints;
    private final IdempotencyProperties properties;
    private final IdempotencyMetrics metrics;

    /**
     * Creates the strategy.
     *
     * @param store        the claim store
     * @param fingerprints hashes the record's value, so a reused key with a changed payload is caught
     * @param properties   the configuration
     * @param metrics      what this module reports about itself
     */
    public IdempotentRecordFilterStrategy(IdempotencyStore store, RequestFingerprint fingerprints,
                                          IdempotencyProperties properties, IdempotencyMetrics metrics) {
        this.store = store;
        this.fingerprints = fingerprints;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Whether the record has already been acted on.
     *
     * @param record the record
     * @return {@code true} to discard it, which is what a duplicate is
     */
    @Override
    public boolean filter(ConsumerRecord<Object, Object> record) {
        String scope = IdempotencyScopes.forTopic(record.topic());
        String key = keyOf(record);
        if (key == null) {
            // Nothing to claim. A record with neither a key nor the configured header is let through
            // rather than refused: refusing would drop it permanently, and a producer that sends no key
            // has chosen at-least-once semantics for its own messages.
            log.debug("Record at {}-{}@{} carries no dedup key; letting it through",
                    record.topic(), record.partition(), record.offset());
            return false;
        }
        String fingerprint = fingerprintOf(record, scope);
        ClaimResult claim = store.claim(new ClaimRequest(
                properties.getKafka().getMode(), scope, key, UUID.randomUUID(), fingerprint,
                properties.ttlFor(scope), properties.leaseFor(scope)));
        if (claim.fingerprintMismatch(fingerprint)) {
            // Only reachable when the key comes from a producer-supplied header: two records cannot share
            // topic, partition and offset. It means the producer reused one key for two payloads, which is
            // a bug in the producer - and the record is still discarded, because the alternative is to
            // guess which of the two payloads was meant and act on it. Counted so the bug is visible, and
            // logged at warn because nobody will report it.
            metrics.fingerprintMismatch(scope);
            log.warn("Record at {}-{}@{} reuses dedup key {} with a different payload; discarding it",
                    record.topic(), record.partition(), record.offset(), key);
            return true;
        }
        if (claim.won()) {
            return false;
        }
        log.info("Discarding a redelivery of {} at {}-{}@{}: key already claimed by request {}",
                key, record.topic(), record.partition(), record.offset(), claim.owner());
        metrics.replayed(scope);
        return true;
    }

    /**
     * The key this record is claimed under.
     *
     * <p>Either a configured header, or the record's own coordinates. The coordinates identify a
     * <em>delivery</em> rather than a message, which is the right default for at-least-once redelivery of
     * the same record - a redelivery has the same topic, partition and offset by definition. It is the
     * wrong choice when a producer republishes the same logical event to a new offset, which is what the
     * header is for: only the producer knows that two records are one event.
     *
     * <p>Not the record's message key, which identifies a partition's worth of related records - an
     * account id, a user id - and would dedup every event about one entity down to the first one.
     */
    private String keyOf(ConsumerRecord<Object, Object> record) {
        String headerName = properties.getKafka().getKeyHeader();
        if (headerName == null || headerName.isBlank()) {
            return record.topic() + ":" + record.partition() + ":" + record.offset();
        }
        Header header = record.headers().lastHeader(headerName);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * The record's fingerprint, when fingerprinting is on and the value is bytes we can hash.
     *
     * <p>Only a {@code byte[]} or a {@code String} value is fingerprinted. A deserialised object would
     * have to be re-serialised to hash it, and two serialisations of one object are not guaranteed to be
     * byte-identical - which would make every redelivery of a deserialised record look like a fingerprint
     * mismatch and refuse it. Returning null there is correct: the claim still dedups, it just does not
     * also verify.
     */
    private String fingerprintOf(ConsumerRecord<Object, Object> record, String scope) {
        if (!properties.getKafka().isFingerprintRecords()) {
            return null;
        }
        Object value = record.value();
        if (value instanceof byte[] bytes) {
            return fingerprints.ofPayload(scope, bytes);
        }
        if (value instanceof String text) {
            return fingerprints.ofPayload(scope, text.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }
}
