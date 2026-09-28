package ru.ludwigandreas.messaging.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * The Micrometer binding.
 *
 * <p>Counters are resolved through the registry on every call, which is what the registry is built for;
 * the one exception is the silence gauge, which has to hold a reference to the value it reports and is
 * therefore cached per topic - the same arrangement {@code MicrometerIngestMetrics} uses for its
 * missing-file gauge, and for the same reason.
 */
public class MicrometerMessagingMetrics implements MessagingMetrics {

    private static final String CONSUMED = "ludwig.messaging.consumed";
    private static final String RETRIES = "ludwig.messaging.retries";
    private static final String DEAD_LETTERED = "ludwig.messaging.dead.lettered";
    private static final String DROPPED = "ludwig.messaging.dropped";

    /**
     * The gauge worth alerting on - see {@link MessagingMetrics} for why the absence of a record is the
     * failure a low-traffic topic actually suffers and why lag cannot see it.
     */
    private static final String SILENT = "ludwig.messaging.silent";

    private final MeterRegistry registry;

    /**
     * The silence suppliers, held so they stay reachable.
     *
     * <p>A map rather than a set of topic names, and the reference is the point rather than the
     * bookkeeping. Micrometer holds a gauge's state object by a <em>weak</em> reference, deliberately, so
     * that a gauge over a short-lived object cannot keep it alive. A supplier created inside
     * {@code ConsumerActivityMonitor.watch} and handed straight to the registry has no other referent, so
     * the next garbage collection would collect it and the gauge would start reporting {@code NaN} - a
     * silence signal that silently stops working, which is the exact failure the signal exists to report.
     *
     * <p>It also makes a double registration harmless: Micrometer keeps the first gauge under a name and
     * tag set and ignores a second, so registering twice would quietly leave the first supplier in place.
     */
    private final Map<String, IntSupplier> silenceSuppliers = new ConcurrentHashMap<>();

    /**
     * Creates the binding.
     *
     * @param registry the meter registry
     */
    public MicrometerMessagingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void recordConsumed(String topic) {
        registry.counter(CONSUMED, "topic", topic).increment();
    }

    @Override
    public void recordRetry(String topic) {
        registry.counter(RETRIES, "topic", topic).increment();
    }

    @Override
    public void recordDeadLettered(String topic, String deadLetterTopic, String exception) {
        // The exception's type is a tag; its message deliberately is not. A tag is a cardinality
        // commitment, and an exception message routinely contains an id, a row count or a quoted
        // fragment of the payload - which would make one series per record and put payload text in the
        // metrics backend.
        registry.counter(DEAD_LETTERED, "topic", topic, "dlt", deadLetterTopic, "exception", exception)
                .increment();
    }

    @Override
    public void recordDropped(String topic) {
        registry.counter(DROPPED, "topic", topic).increment();
    }

    @Override
    public void registerSilence(String topic, IntSupplier silent) {
        if (silenceSuppliers.putIfAbsent(topic, silent) == null) {
            registry.gauge(SILENT, Tags.of("topic", topic), silent, IntSupplier::getAsInt);
        }
    }
}
