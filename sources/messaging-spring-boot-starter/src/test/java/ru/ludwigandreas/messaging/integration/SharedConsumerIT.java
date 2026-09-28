package ru.ludwigandreas.messaging.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.messaging.api.DeadLetterTopics;
import ru.ludwigandreas.messaging.api.MessageHeaders;
import ru.ludwigandreas.messaging.integration.MessagingTestApplication.Recorder;
import ru.ludwigandreas.messaging.metrics.ConsumerActivityMonitor;
import ru.ludwigandreas.testsupport.container.Containers;

/**
 * The shared consumer against a real broker.
 *
 * <p>Testcontainers rather than spring-kafka's embedded broker, and the reason is what is under test:
 * every behaviour here is about where a record ends up when it cannot be processed, and a dead-letter topic
 * that no broker actually has is a test of the wiring rather than of the outcome. Three of these tests are
 * regression tests for defects that were live in this repository - two of which lose records - and a
 * regression test that cannot observe the record is not one.
 *
 * <p>Backoffs are configured down from the platform's one-second start to keep the suite quick, but the
 * <em>shape</em> is the platform's: it still grows, and the test asserts that it grows rather than asserting
 * a number.
 */
@SpringBootTest(classes = MessagingTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.producer.properties.max.block.ms=10000",
        // One thread per consumer, so a redelivery is genuinely sequential and the ordering assertion is not
        // racing itself.
        "spring.kafka.listener.concurrency=1",

        // The typed consumer accepts version 1 only, which is what installs the version gate at all.
        "ludwig.messaging.consumers.typed.accepted-versions.max=1",
        "ludwig.messaging.consumers.typed.retry.initial-backoff=200ms",
        "ludwig.messaging.consumers.typed.retry.max-backoff=2s",
        "ludwig.messaging.consumers.typed.retry.max-attempts=3",
        // Dedup is on by default; this consumer has no dedup key and must not be deduplicated by offset.
        "ludwig.messaging.consumers.typed.dedup=false",

        "ludwig.messaging.consumers.text.retry.initial-backoff=100ms",
        "ludwig.messaging.consumers.text.retry.max-backoff=500ms",
        "ludwig.messaging.consumers.text.retry.max-attempts=3",
        "ludwig.messaging.consumers.text.dedup=false",

        "ludwig.messaging.consumers.ordered.retry.initial-backoff=100ms",
        "ludwig.messaging.consumers.ordered.retry.max-attempts=3",
        "ludwig.messaging.consumers.ordered.dedup=false",

        // The one consumer the dedup filter is meant to be attached to.
        "ludwig.messaging.consumers.deduped.retry.max-attempts=2",

        "ludwig.messaging.consumers.quiet.dedup=false",
        "ludwig.messaging.consumers.quiet.silence-threshold=1s"
})
class SharedConsumerIT {

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", Containers.kafka()::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<String, String> template;

    @Autowired
    private Recorder recorder;

    @Autowired
    private ConsumerActivityMonitor activityMonitor;

    @BeforeEach
    void resetTheListeners() {
        recorder.textThrows.set(false);
        recorder.gaps.clear();
    }

    /**
     * A malformed payload reaches the dead-letter topic on its first attempt, not its third.
     *
     * <p>{@code NotificationMessagingConfig}'s reasoning, which the shared handler carries: a payload that
     * could not be deserialized will never deserialize, so it goes straight to the dead-letter topic instead
     * of spending its attempts proving it. The assertion is on the attempt budget, not only on the
     * destination - spending three attempts and six seconds of backoff on a record that cannot change is how
     * a poison record delays every record behind it.
     */
    @Test
    @DisplayName("a poison record is dead-lettered on the first attempt")
    void deadLettersAPoisonRecordImmediately() {
        String marker = "poison-" + UUID.randomUUID();
        send(MessagingTopics.TYPED, marker, "{ this is not json", 1);

        ConsumerRecord<String, String> dead = awaitDeadLetter(MessagingTopics.TYPED, marker);

        assertThat(dead.value()).isEqualTo("{ this is not json");
        assertThat(headerOf(dead, KafkaHeaders.DLT_EXCEPTION_FQCN))
                .contains("DeserializationException");
        assertThat(recorder.on(MessagingTopics.TYPED))
                .as("the listener never ran: the record never became an object")
                .doesNotContain(marker);
    }

    /**
     * A retryable failure is retried with growing gaps and then dead-lettered, and the dead-letter record
     * carries the original payload plus the exception headers an operator needs.
     */
    @Test
    @DisplayName("a retryable failure is retried with growing gaps, then dead-lettered with its payload")
    void retriesWithBackoffThenDeadLetters() {
        send(MessagingTopics.TYPED, "retry", "{\"value\":\"boom\"}", 1);

        ConsumerRecord<String, String> dead = awaitDeadLetter(MessagingTopics.TYPED, "retry");

        assertThat(recorder.on(MessagingTopics.TYPED)).as("three attempts, including the first")
                .filteredOn("boom"::equals).hasSize(3);
        assertThat(dead.value()).as("the original payload, unaltered").isEqualTo("{\"value\":\"boom\"}");
        // Two headers, because Spring Kafka records the container's wrapper in one and the listener's own
        // failure in the other. An operator reading only the first sees ListenerExecutionFailedException,
        // which is true of every listener failure and therefore says nothing.
        assertThat(headerOf(dead, KafkaHeaders.DLT_EXCEPTION_FQCN)).contains("ListenerExecutionFailed");
        assertThat(headerOf(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).contains("IllegalStateException");
        assertThat(headerOf(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(MessagingTopics.TYPED);
        assertThat(headerOf(dead, KafkaHeaders.DLT_ORIGINAL_OFFSET)).isNotNull();

        List<Duration> gaps = new ArrayList<>(recorder.gaps);
        assertThat(gaps).as("at least two gaps between three attempts").hasSizeGreaterThanOrEqualTo(2);
        assertThat(gaps.get(1)).as("the backoff grows rather than repeating")
                .isGreaterThan(gaps.get(0));
    }

    /**
     * The user-settings regression, written as a test of the new behaviour that would have failed before.
     *
     * <p>Under that module's own configuration - {@code AckMode.MANUAL} with no error handler - a listener
     * that threw before acknowledging never acknowledged, so this record was redelivered forever, with no
     * backoff, at full speed. The assertion that would have failed is the bounded invocation count: under
     * the old configuration it would have kept climbing, and there would have been no dead-letter record to
     * find because the module had no dead-letter topic.
     */
    @Test
    @DisplayName("a throwing listener dead-letters instead of being redelivered forever")
    void boundsRedeliveryAndDeadLetters() {
        recorder.textThrows.set(true);
        String marker = "settings-" + UUID.randomUUID();
        send(MessagingTopics.TEXT, marker, marker, 1);

        awaitDeadLetter(MessagingTopics.TEXT, marker);

        int attempts = (int) recorder.on(MessagingTopics.TEXT).stream().filter(marker::equals).count();
        assertThat(attempts).as("bounded by the configured budget, not unbounded").isEqualTo(3);

        // Held rather than awaited: the assertion is that redelivery has stopped, which has to survive the
        // passage of time rather than merely be true once.
        sleep(Duration.ofMillis(1500));
        assertThat(recorder.on(MessagingTopics.TEXT).stream().filter(marker::equals).count())
                .as("no further redelivery after the record was dead-lettered").isEqualTo(attempts);
    }

    /**
     * The identity-projection regression. That module declared no container factory and so inherited Boot's
     * default error handler: ten attempts with no backoff, and then <em>log and move on</em> - the record
     * dropped, with no trace but a log line in a service nobody tails.
     *
     * <p>The assertion is the presence of the record somewhere durable. Under the old behaviour there was
     * nowhere to look for it, which is exactly why the defect survived.
     */
    @Test
    @DisplayName("a record that cannot be processed is preserved, not silently dropped")
    void neverSilentlyDropsARecord() {
        recorder.textThrows.set(true);
        String marker = "identity-" + UUID.randomUUID();
        send(MessagingTopics.TEXT, marker, marker, 1);

        ConsumerRecord<String, String> dead = awaitDeadLetter(MessagingTopics.TEXT, marker);

        assertThat(dead.value()).isEqualTo(marker);
        assertThat(headerOf(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(MessagingTopics.TEXT);
    }

    /**
     * Dedup comes from the filter strategy in the context, attached to every factory this module builds - so
     * a service gets it by configuration rather than by remembering to wire it into its own factory.
     *
     * <p>The second assertion matters as much as the first: a filtered record is acknowledged and its offset
     * committed, so it must not appear on the dead-letter topic. A duplicate is not a failure.
     */
    @Test
    @DisplayName("the same record delivered twice has one effect, and the second is not a failure")
    void deduplicatesWithoutFailing() {
        String key = "dedup-" + UUID.randomUUID();
        send(MessagingTopics.DEDUPED, key, key, 1, key);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(recorder.on(MessagingTopics.DEDUPED)).contains(key));

        send(MessagingTopics.DEDUPED, key, key, 1, key);
        sleep(Duration.ofSeconds(2));

        assertThat(recorder.on(MessagingTopics.DEDUPED)).filteredOn(key::equals).hasSize(1);
        assertThat(deadLetters(MessagingTopics.DEDUPED)).noneMatch(record -> key.equals(record.value()));
    }

    /**
     * A blocking retry preserves same-key order, which is the property the producer's {@code orderingKey}
     * promises and the reason non-blocking retry topics are opt-in.
     *
     * <p>The middle record fails once. If retries were non-blocking it would be republished and re-applied
     * after the third record; because they block, the partition pauses and the three arrive in order.
     */
    @Test
    @DisplayName("a blocking retry preserves same-key order")
    void preservesOrderAcrossARetry() {
        String key = "ordering-" + UUID.randomUUID();
        send(MessagingTopics.ORDERED, key, key + "-a", 1);
        send(MessagingTopics.ORDERED, key, key + "-b!", 1);
        send(MessagingTopics.ORDERED, key, key + "-c", 1);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(recorder.on(MessagingTopics.ORDERED))
                        .containsSubsequence(key + "-a", key + "-b!", key + "-c"));
    }

    /**
     * An unknown payload schema version is refused before the payload is deserialized.
     *
     * <p>The two assertions are the whole point. The record reaches the dead-letter topic, and the listener
     * never ran - so the bytes were never mapped onto this consumer's current type, which is the silent
     * data-corruption path the gate exists to close. A consumer that ignores the version writes a row with a
     * renamed field left at its Java default, and nothing anywhere records that it happened.
     */
    @Test
    @DisplayName("an unknown event version is dead-lettered, never deserialized")
    void refusesAnUnknownEventVersion() {
        String marker = "version-" + UUID.randomUUID();
        send(MessagingTopics.TYPED, marker, "{\"value\":\"" + marker + "\"}", 2);

        ConsumerRecord<String, String> dead = awaitDeadLetter(MessagingTopics.TYPED, marker);

        // The refusal's own sentence is in the stack-trace header, not the message header:
        // ErrorHandlingDeserializer wraps the cause, and its own message is the flat "failed to
        // deserialize". Asserted on the stack trace because the sentence is what an operator reads to learn
        // that this is a version problem rather than malformed JSON.
        assertThat(headerOf(dead, KafkaHeaders.DLT_EXCEPTION_FQCN)).contains("DeserializationException");
        assertThat(headerOf(dead, KafkaHeaders.DLT_EXCEPTION_STACKTRACE))
                .contains("UnsupportedEventVersionException")
                .contains("event version 2")
                .contains("1..1");
        assertThat(recorder.on(MessagingTopics.TYPED))
                .as("the payload was never mapped onto the consumer's type").doesNotContain(marker);
    }

    /**
     * The security default: the type is fixed by this consumer, never taken from a record header. A
     * header-driven deserializer instantiates whatever class a producer names, which is a deserialization
     * gadget - and a topic is not an authenticated caller.
     */
    @Test
    @DisplayName("a record naming another type in a header does not cause that type to be used")
    void ignoresAProducerSuppliedType() {
        String marker = "typed-" + UUID.randomUUID();
        ProducerRecord<String, String> record = new ProducerRecord<>(
                MessagingTopics.TYPED, marker, "{\"value\":\"" + marker + "\"}");
        record.headers().add(MessageHeaders.EVENT_VERSION, "1".getBytes(StandardCharsets.UTF_8));
        record.headers().add("__TypeId__",
                MessagingTestApplication.OtherPayload.class.getName().getBytes(StandardCharsets.UTF_8));
        template.send(record);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(recorder.on(MessagingTopics.TYPED)).contains(marker));
        assertThat(deadLetters(MessagingTopics.TYPED))
                .as("the named type was ignored, not attempted and failed")
                .noneMatch(dead -> marker.equals(dead.key()));
    }

    /**
     * The silence signal, which is the metric consumer lag cannot provide: a topic with no traffic and a
     * consumer whose container died both read as lag zero.
     *
     * <p>Armed at startup, so this consumer is already silent by the time the suite reaches it - which is
     * the behaviour that matters, because a consumer that never receives anything is the case an
     * arm-on-first-record design would never report.
     */
    @Test
    @DisplayName("a topic that has stopped delivering reports silence, and a record clears it")
    void reportsSilence() {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(activityMonitor.silent(MessagingTopics.QUIET)).isTrue());

        String marker = "quiet-" + UUID.randomUUID();
        send(MessagingTopics.QUIET, marker, marker, 1);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(recorder.on(MessagingTopics.QUIET)).contains(marker);
            assertThat(activityMonitor.silent(MessagingTopics.QUIET)).isFalse();
        });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(activityMonitor.silent(MessagingTopics.QUIET))
                        .as("silent again once the window passes").isTrue());
    }

    private void send(String topic, String key, String payload, int eventVersion) {
        send(topic, key, payload, eventVersion, null);
    }

    private void send(String topic, String key, String payload, int eventVersion, String idempotencyKey) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        record.headers().add(MessageHeaders.EVENT_VERSION,
                String.valueOf(eventVersion).getBytes(StandardCharsets.UTF_8));
        if (idempotencyKey != null) {
            record.headers().add(MessageHeaders.IDEMPOTENCY_KEY,
                    idempotencyKey.getBytes(StandardCharsets.UTF_8));
        }
        template.send(record);
        template.flush();
    }

    /** Waits for a record with this key to appear on the topic's dead-letter topic. */
    private ConsumerRecord<String, String> awaitDeadLetter(String topic, String key) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            Optional<ConsumerRecord<String, String>> match = deadLetters(topic).stream()
                    .filter(record -> key.equals(record.key()))
                    .findFirst();
            assertThat(match).as("a dead-letter record keyed %s on %s", key, topic).isPresent();
            found.clear();
            found.add(match.get());
        });
        return found.get(0);
    }

    /**
     * Everything currently on a topic's dead-letter topic.
     *
     * <p>Read with a plain consumer and a fresh group each time rather than through a listener, because a
     * listener would commit offsets and the next assertion in the same test could not see what the previous
     * one did.
     */
    private List<ConsumerRecord<String, String>> deadLetters(String topic) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Containers.kafka().getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        String deadLetterTopic = DeadLetterTopics.forTopic(topic, DeadLetterTopics.DEFAULT_SUFFIX);
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(deadLetterTopic));
            ConsumerRecords<String, String> polled = consumer.poll(Duration.ofSeconds(2));
            polled.forEach(records::add);
        }
        return records;
    }

    private static String headerOf(ConsumerRecord<String, String> record, String name) {
        org.apache.kafka.common.header.Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding an assertion", e);
        }
    }
}
