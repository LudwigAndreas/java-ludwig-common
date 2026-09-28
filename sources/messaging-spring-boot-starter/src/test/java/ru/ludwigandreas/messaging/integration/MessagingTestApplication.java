package ru.ludwigandreas.messaging.integration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import ru.ludwigandreas.messaging.api.MessageHeaders;
import ru.ludwigandreas.messaging.config.MessagingAutoConfiguration;
import ru.ludwigandreas.messaging.config.MessagingMetricsAutoConfiguration;
import ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder;

/**
 * The application the container suite runs: five consumers, each built by the platform's factory builder,
 * each configured only by property.
 *
 * <p>Five rather than one because the behaviours under test are per-consumer settings - a version bound, a
 * silence threshold, a dedup filter - and a single consumer would have to be reconfigured between tests,
 * which means restarting a container and losing the thing the suite exists to observe.
 *
 * <p>The autoconfigurations are imported explicitly rather than discovered by
 * {@code @SpringBootApplication}, and that is not fussiness. This module's own test classpath carries
 * {@code spring-boot-starter-data-jpa} and {@code spring-data-redis}, because they arrive with the optional
 * idempotency dependency it compiles against - so a scanning application tries to build a
 * {@code DataSource} and fails on a missing driver before any consumer starts. Naming the four
 * autoconfigurations also states what this suite is actually exercising.
 */
@SpringBootConfiguration
@EnableKafka
@ImportAutoConfiguration({KafkaAutoConfiguration.class, MessagingMetricsAutoConfiguration.class,
        MessagingAutoConfiguration.class})
public class MessagingTestApplication {

    /**
     * A real registry, so the Micrometer binding is the one under test rather than the no-op.
     *
     * @return the registry
     */
    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    /**
     * The listeners, declared as a bean because this configuration deliberately does not component-scan.
     *
     * @param recorder what the listeners write to
     * @return the listeners
     */
    @Bean
    Listeners listeners(Recorder recorder) {
        return new Listeners(recorder);
    }

    /** A JSON payload the typed consumer is fixed to. */
    public record TypedPayload(String value) {
    }

    /** A type that is on the classpath and that no consumer here is fixed to. */
    public record OtherPayload(String value) {
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, TypedPayload> typedFactory(
            ListenerContainerFactoryBuilder builder) {
        return builder.forJsonPayload("typed", TypedPayload.class, MessagingTopics.TYPED);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> textFactory(
            ListenerContainerFactoryBuilder builder) {
        return builder.forTextPayload("text", MessagingTopics.TEXT);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> orderedFactory(
            ListenerContainerFactoryBuilder builder) {
        return builder.forTextPayload("ordered", MessagingTopics.ORDERED);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> dedupedFactory(
            ListenerContainerFactoryBuilder builder) {
        return builder.forTextPayload("deduped", MessagingTopics.DEDUPED);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> quietFactory(
            ListenerContainerFactoryBuilder builder) {
        return builder.forTextPayload("quiet", MessagingTopics.QUIET);
    }

    /**
     * Stands in for {@code IdempotentRecordFilterStrategy}, claiming the producer's dedup key in memory.
     *
     * <p>Deliberately not the real one. What this suite is testing is that the platform's container factory
     * attaches <em>a</em> filter strategy from the context by default and that a filtered record is
     * acknowledged rather than counted as a failure; whether a Postgres claim survives contention is the
     * idempotency starter's own suite's question, and answering it here would mean a second database
     * container proving something already proved.
     */
    @Bean
    RecordFilterStrategy<Object, Object> dedupFilter(Recorder recorder) {
        return record -> {
            org.apache.kafka.common.header.Header header =
                    record.headers().lastHeader(MessageHeaders.IDEMPOTENCY_KEY);
            if (header == null) {
                return false;
            }
            String key = new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
            return !recorder.claimed.add(key);
        };
    }

    @Bean
    Recorder recorder() {
        return new Recorder();
    }

    /** What the listeners saw, and what the tests steer them with. */
    public static class Recorder {

        /** Payloads delivered per topic, in delivery order. */
        public final Map<String, List<String>> delivered = new ConcurrentHashMap<>();

        /** How many times a listener body ran per topic, retries included. */
        public final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();

        /** Gaps between successive deliveries of the same record, to prove the backoff grows. */
        public final List<Duration> gaps = java.util.Collections.synchronizedList(new ArrayList<>());

        /** Whether the text listener should throw. */
        public final AtomicBoolean textThrows = new AtomicBoolean();

        /** Dedup keys already claimed. */
        final java.util.Set<String> claimed = ConcurrentHashMap.newKeySet();

        /** Attempts per record value, so a listener can fail exactly its first delivery. */
        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

        private volatile long lastDelivery;

        void record(String topic, String payload) {
            delivered.computeIfAbsent(topic, key -> java.util.Collections.synchronizedList(new ArrayList<>()))
                    .add(payload);
            invocations.computeIfAbsent(topic, key -> new AtomicInteger()).incrementAndGet();
        }

        void recordGap() {
            long now = System.nanoTime();
            if (lastDelivery != 0) {
                gaps.add(Duration.ofNanos(now - lastDelivery));
            }
            lastDelivery = now;
        }

        /** Payloads seen on a topic, or an empty list. */
        public List<String> on(String topic) {
            return delivered.getOrDefault(topic, List.of());
        }

        /** Listener invocations on a topic. */
        public int invocationsOn(String topic) {
            return invocations.getOrDefault(topic, new AtomicInteger()).get();
        }
    }

    /** The listeners. One per consumer, each doing the least that makes its behaviour observable. */
    public static class Listeners {

        private final Recorder recorder;

        public Listeners(Recorder recorder) {
            this.recorder = recorder;
        }

        /** Records the payload; a value of {@code boom} fails, which is the retryable-failure case. */
        @KafkaListener(topics = MessagingTopics.TYPED, groupId = "it-typed",
                containerFactory = "typedFactory")
        public void typed(ConsumerRecord<String, TypedPayload> record) {
            recorder.recordGap();
            recorder.record(MessagingTopics.TYPED, record.value().value());
            if ("boom".equals(record.value().value())) {
                throw new IllegalStateException("a transient failure");
            }
        }

        /**
         * Throws whenever the test says so, which is the user-settings and identity-projection regression:
         * under their own configurations this record was redelivered forever, or dropped.
         */
        @KafkaListener(topics = MessagingTopics.TEXT, groupId = "it-text", containerFactory = "textFactory")
        public void text(ConsumerRecord<String, String> record) {
            recorder.record(MessagingTopics.TEXT, record.value());
            if (recorder.textThrows.get()) {
                throw new IllegalStateException("the projection could not apply this");
            }
        }

        /**
         * Fails the first delivery of a record whose value ends in {@code !}, then accepts it.
         *
         * <p>One failure rather than a permanent one, because what this proves is that a blocking retry
         * preserves order: the partition pauses, the record is retried in place, and the records behind it
         * are delivered after it rather than ahead of it.
         */
        @KafkaListener(topics = MessagingTopics.ORDERED, groupId = "it-ordered",
                containerFactory = "orderedFactory")
        public void ordered(ConsumerRecord<String, String> record) {
            String value = record.value();
            int attempt = recorder.attempts.computeIfAbsent(value, key -> new AtomicInteger())
                    .incrementAndGet();
            if (value.endsWith("!") && attempt == 1) {
                throw new IllegalStateException("fails once, on purpose");
            }
            recorder.record(MessagingTopics.ORDERED, value);
        }

        @KafkaListener(topics = MessagingTopics.DEDUPED, groupId = "it-deduped",
                containerFactory = "dedupedFactory")
        public void deduped(ConsumerRecord<String, String> record) {
            recorder.record(MessagingTopics.DEDUPED, record.value());
        }

        @KafkaListener(topics = MessagingTopics.QUIET, groupId = "it-quiet",
                containerFactory = "quietFactory")
        public void quiet(ConsumerRecord<String, String> record) {
            recorder.record(MessagingTopics.QUIET, record.value());
        }
    }
}
