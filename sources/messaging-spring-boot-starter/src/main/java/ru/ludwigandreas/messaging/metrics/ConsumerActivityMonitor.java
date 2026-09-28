package ru.ludwigandreas.messaging.metrics;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.RecordInterceptor;

/**
 * Notices that a topic has stopped delivering.
 *
 * <p>This is the operationally important half of this module's instrumentation, and the platform had
 * nothing like it. Lag answers "how far behind am I", which is the wrong question for a topic that
 * receives a few records an hour: a consumer whose container died, a consumer whose group id was
 * mistyped so it was never assigned a partition, and a topic that genuinely has nothing to say all
 * report lag zero. Only a clock can tell them apart, and only if somebody says how long an absence is
 * supposed to be tolerable.
 *
 * <p>The same failure as file-ingest's file that never arrived, and it presents the same way: a healthy
 * pod, an empty log, and a projection quietly going stale for as long as anyone lets it.
 *
 * <h2>Armed at registration, not at first record</h2>
 *
 * <p>A topic's clock starts when its container factory is built, which is startup. The alternative -
 * starting it at the first record - would mean a consumer that never receives anything never reports
 * silence, which is the one case that matters most. Starting it at startup does mean a deployment whose
 * threshold is shorter than its own restart-to-first-record gap reports silence briefly after every
 * deploy; that is the right way round, because it is visible and self-correcting, whereas the other way
 * round is invisible and permanent.
 *
 * <h2>Why a {@code RecordInterceptor}</h2>
 *
 * <p>Because it is the one hook that sees every record the container hands to a listener, including the
 * one whose listener then throws - and a record that arrived and failed is not silence. It is composed
 * with whatever other interceptor the application has rather than replacing it, which matters here:
 * {@code observability-spring-boot-starter} contributes one, and a factory that overwrote it would drop
 * the correlation id on every consumer this module configures.
 */
public class ConsumerActivityMonitor implements RecordInterceptor<Object, Object> {

    /** Per topic: when something was last seen, as epoch milliseconds. */
    private final Map<String, AtomicLong> lastSeen = new ConcurrentHashMap<>();

    /** Per topic: how long an absence is tolerable. Absent means the topic has no silence signal. */
    private final Map<String, Duration> thresholds = new ConcurrentHashMap<>();

    private final MessagingMetrics metrics;
    private final Clock clock;

    /**
     * Creates the monitor.
     *
     * @param metrics where the signal goes
     * @param clock   injected so the silence test is testable without waiting out a real threshold
     */
    public ConsumerActivityMonitor(MessagingMetrics metrics, Clock clock) {
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Starts watching a topic, and registers its gauge.
     *
     * @param topic     the topic
     * @param threshold how long nothing may arrive before the topic counts as silent; {@code null} or
     *                  non-positive registers no gauge, which is how a topic whose traffic pattern has no
     *                  meaningful expectation opts out
     */
    public void watch(String topic, Duration threshold) {
        lastSeen.computeIfAbsent(topic, name -> new AtomicLong(clock.millis()));
        if (threshold == null || threshold.isZero() || threshold.isNegative()) {
            return;
        }
        // The gauge is registered on the first watch only; the supplier reads the threshold from the map on
        // every scrape, so a second watch with a different threshold takes effect without a second gauge.
        // Registering again would be worse than a no-op: Micrometer keeps the first gauge under a name and
        // tag set and ignores the second, so the caller would believe it had replaced something it had not.
        boolean firstWatch = thresholds.put(topic, threshold) == null;
        if (firstWatch) {
            metrics.registerSilence(topic, () -> silent(topic) ? 1 : 0);
        }
    }

    /**
     * Whether the topic has gone silent.
     *
     * @param topic the topic
     * @return {@code true} when nothing has been consumed within the configured window
     */
    public boolean silent(String topic) {
        Duration threshold = thresholds.get(topic);
        AtomicLong seen = lastSeen.get(topic);
        if (threshold == null || seen == null) {
            return false;
        }
        return clock.millis() - seen.get() > threshold.toMillis();
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        // computeIfAbsent rather than get-then-set: a consumer subscribing by pattern reaches here with
        // a topic nobody registered, and losing its activity would make a watched topic look silent
        // because a sibling was doing all the work.
        lastSeen.computeIfAbsent(record.topic(), name -> new AtomicLong()).set(clock.millis());
        metrics.recordConsumed(record.topic());
        return record;
    }
}
