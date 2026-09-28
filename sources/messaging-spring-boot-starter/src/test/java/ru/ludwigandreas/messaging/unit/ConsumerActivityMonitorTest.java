package ru.ludwigandreas.messaging.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntSupplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.messaging.metrics.ConsumerActivityMonitor;
import ru.ludwigandreas.messaging.metrics.MessagingMetrics;
import ru.ludwigandreas.messaging.metrics.NoopMessagingMetrics;

/**
 * The silence signal, which is the metric lag cannot replace.
 *
 * <p>A fixed clock rather than a real wait: the property under test is "longer than the configured
 * window", and a test that slept for the window would either be slow or configure a window so short it
 * proved nothing about the arithmetic.
 */
class ConsumerActivityMonitorTest {

    private Instant now = Instant.parse("2026-09-26T12:00:00Z");

    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    private final RecordingMetrics metrics = new RecordingMetrics();
    private final ConsumerActivityMonitor monitor = new ConsumerActivityMonitor(metrics, clock);

    /**
     * Armed at registration, not at the first record. A consumer that never receives anything is the case
     * that matters most, and it would never report silence if the clock started on first delivery.
     */
    @Test
    @DisplayName("a watched topic that never delivers goes silent")
    void reportsSilenceForATopicThatNeverDelivers() {
        monitor.watch("orders", Duration.ofMinutes(10));

        assertThat(monitor.silent("orders")).isFalse();
        now = now.plus(Duration.ofMinutes(11));
        assertThat(monitor.silent("orders")).isTrue();
    }

    @Test
    @DisplayName("a consumed record rearms the window")
    void rearmsOnARecord() {
        monitor.watch("orders", Duration.ofMinutes(10));
        now = now.plus(Duration.ofMinutes(9));
        monitor.intercept(record("orders"), null);
        now = now.plus(Duration.ofMinutes(9));

        assertThat(monitor.silent("orders")).isFalse();
        assertThat(metrics.consumed).containsEntry("orders", 1);
    }

    @Test
    @DisplayName("a topic with no configured threshold has no silence signal and no gauge")
    void doesNotReportSilenceWithoutAThreshold() {
        monitor.watch("orders", null);
        now = now.plus(Duration.ofDays(30));

        assertThat(monitor.silent("orders")).isFalse();
        assertThat(metrics.gauges).isEmpty();
    }

    /**
     * Registered once, however often a topic is watched. Micrometer keeps the first gauge under a name and
     * tag set and ignores a second, so a re-registration would leave the first supplier in place while the
     * caller believed it had replaced it.
     */
    @Test
    @DisplayName("watching a topic twice registers one gauge")
    void registersOneGaugePerTopic() {
        monitor.watch("orders", Duration.ofMinutes(10));
        monitor.watch("orders", Duration.ofMinutes(20));

        assertThat(metrics.registrations).containsExactly("orders");
    }

    @Test
    @DisplayName("the gauge reads 1 exactly while the topic is silent")
    void publishesTheGauge() {
        monitor.watch("orders", Duration.ofMinutes(10));
        IntSupplier gauge = metrics.gauges.get("orders");

        assertThat(gauge.getAsInt()).isZero();
        now = now.plus(Duration.ofMinutes(11));
        assertThat(gauge.getAsInt()).isEqualTo(1);
    }

    /**
     * A consumer subscribing by pattern reaches the interceptor with a topic nobody registered. Losing its
     * activity would make a watched topic look silent because a sibling was doing all the work.
     */
    @Test
    @DisplayName("an unwatched topic's activity is still recorded")
    void recordsActivityForAnUnwatchedTopic() {
        monitor.intercept(record("surprise"), null);

        assertThat(metrics.consumed).containsEntry("surprise", 1);
        assertThat(monitor.silent("surprise")).isFalse();
    }

    private static ConsumerRecord<Object, Object> record(String topic) {
        return new ConsumerRecord<>(topic, 0, 0L, "k", "v");
    }

    /** Records what was published, which is all these tests need from the metrics seam. */
    private static final class RecordingMetrics extends NoopMessagingMetrics implements MessagingMetrics {

        private final Map<String, Integer> consumed = new LinkedHashMap<>();
        private final Map<String, IntSupplier> gauges = new LinkedHashMap<>();
        private final java.util.List<String> registrations = new java.util.ArrayList<>();

        @Override
        public void recordConsumed(String topic) {
            consumed.merge(topic, 1, Integer::sum);
        }

        @Override
        public void registerSilence(String topic, IntSupplier silent) {
            gauges.put(topic, silent);
            registrations.add(topic);
        }
    }
}
