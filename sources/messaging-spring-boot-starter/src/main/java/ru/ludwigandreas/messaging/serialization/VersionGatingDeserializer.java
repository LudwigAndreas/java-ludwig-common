package ru.ludwigandreas.messaging.serialization;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Deserializer;
import ru.ludwigandreas.messaging.api.InboundEnvelope;
import ru.ludwigandreas.messaging.api.MessageHeaders;
import ru.ludwigandreas.messaging.error.UnsupportedEventVersionException;

/**
 * Refuses a record whose payload schema version this consumer does not accept, before the payload is
 * deserialized.
 *
 * <p>{@code eventVersion} has been on the producer's envelope since {@code OutboxEvent} was written and
 * nothing on the platform read it. That is not a missing nicety. A payload evolving under a consumer
 * that ignores its version is a silent data-corruption path: Jackson maps the bytes onto the consumer's
 * <em>current</em> type, a field that was renamed or split arrives as its Java default, the listener
 * writes a row, and nothing anywhere records that a version 2 event was applied by a version 1
 * consumer. The row looks like an ordinary row. It is found, if at all, months later by a human
 * noticing that some records have no value in a column.
 *
 * <h2>Why a deserializer and not an interceptor or a listener check</h2>
 *
 * <p>Because "never deserialized into the current type" has to mean never. A
 * {@code RecordInterceptor} runs after the container has deserialized the value, so by the time it
 * could refuse the record the mapping has already happened - the object exists, and the check is a
 * statement about what the listener does with it rather than about what was constructed. Kafka's
 * {@code Deserializer} interface has a three-argument {@code deserialize} that receives the record's
 * headers, which is the one place in the pipeline that can read the version and decide <em>not</em> to
 * parse.
 *
 * <p>The refusal surfaces as a {@code DeserializationException} once
 * {@code ErrorHandlingDeserializer} has wrapped it, and that exception is registered non-retryable on
 * the shared error handler - so the record is dead-lettered on its first attempt. Correct: a version
 * does not change on redelivery, and spending four attempts and thirty seconds of backoff proving it
 * only delays the records behind it.
 *
 * <h2>Routing, for the deployment that wants it</h2>
 *
 * <p>This class rejects; it does not route. Routing an unknown version to a topic of its own is the
 * dead-letter topic with a different name, and this module already has one - a consumer that wants a
 * version-specific destination sets a dead-letter suffix on that consumer's configuration and gets
 * exactly that, without a second mechanism. What must not happen, and what this class makes
 * impossible, is the third option: deserializing it anyway.
 *
 * @param <T> the payload type
 */
public class VersionGatingDeserializer<T> implements Deserializer<T> {

    private final Deserializer<T> delegate;
    private final int minAccepted;
    private final int maxAccepted;

    /**
     * Wraps a deserializer in a version gate.
     *
     * @param delegate    the deserializer that parses the payload once the version is accepted
     * @param minAccepted the lowest accepted version, inclusive
     * @param maxAccepted the highest accepted version, inclusive
     */
    public VersionGatingDeserializer(Deserializer<T> delegate, int minAccepted, int maxAccepted) {
        if (minAccepted > maxAccepted) {
            throw new IllegalArgumentException(
                    "An accepted version range runs low to high: " + minAccepted + ".." + maxAccepted);
        }
        this.delegate = delegate;
        this.minAccepted = minAccepted;
        this.maxAccepted = maxAccepted;
    }

    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        delegate.configure(configs, isKey);
    }

    /**
     * The two-argument form, which Kafka calls when there are no headers to offer.
     *
     * <p>It cannot gate - there is no version to read - and it delegates rather than refusing. Refusing
     * here would break every path that deserializes a value outside a consumer poll, the dead-letter
     * tooling's own included.
     */
    @Override
    public T deserialize(String topic, byte[] data) {
        return delegate.deserialize(topic, data);
    }

    @Override
    public T deserialize(String topic, Headers headers, byte[] data) {
        int version = versionOf(headers);
        if (version < minAccepted || version > maxAccepted) {
            throw new UnsupportedEventVersionException(topic, version, minAccepted, maxAccepted);
        }
        return delegate.deserialize(topic, headers, data);
    }

    @Override
    public void close() {
        delegate.close();
    }

    /**
     * The declared version, or the default for a record that carries no header.
     *
     * <p>An absent header reads as version 1 for the reason {@link InboundEnvelope} gives: that is the
     * producer's own default, so a record from a producer predating the header is version 1 rather than
     * an unknown version that this gate would dead-letter. An unparseable header is <em>not</em>
     * treated the same way - it reads as zero, which is outside every legitimate range and is therefore
     * refused. A version header nobody can parse is a producer bug, and the failure mode of guessing is
     * the one this whole class exists to prevent.
     */
    private static int versionOf(Headers headers) {
        Header header = headers == null ? null : headers.lastHeader(MessageHeaders.EVENT_VERSION);
        if (header == null && headers != null) {
            header = headers.lastHeader(MessageHeaders.LEGACY_EVENT_VERSION);
        }
        if (header == null || header.value() == null) {
            return InboundEnvelope.DEFAULT_EVENT_VERSION;
        }
        try {
            return Integer.parseInt(new String(header.value(), StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
