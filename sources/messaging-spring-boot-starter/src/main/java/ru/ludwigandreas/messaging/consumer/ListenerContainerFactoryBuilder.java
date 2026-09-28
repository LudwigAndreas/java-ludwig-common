package ru.ludwigandreas.messaging.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.CompositeRecordInterceptor;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.backoff.ExponentialBackOff;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.messaging.api.DeadLetterTopics;
import ru.ludwigandreas.messaging.api.EnvelopeReader;
import ru.ludwigandreas.messaging.dlt.AuditingDeadLetterRecoverer;
import ru.ludwigandreas.messaging.dlt.DeadLetterTemplates;
import ru.ludwigandreas.messaging.metrics.ConsumerActivityMonitor;
import ru.ludwigandreas.messaging.metrics.MessagingMetrics;
import ru.ludwigandreas.messaging.serialization.PayloadDeserializers;
import ru.ludwigandreas.messaging.serialization.VersionGatingDeserializer;
import ru.ludwigandreas.messaging.settings.MessagingProperties;
import ru.ludwigandreas.messaging.settings.ResolvedConsumerSettings;

/**
 * Builds the platform's listener container factory: one set of decisions, taken once, that a consumer
 * asks for by name.
 *
 * <p>This class is the consolidation. Consumption was wired three times in this repository and no two
 * wirings agreed on anything that mattered - the container factory, the error handler, the backoff, the
 * acknowledgement mode, the deserializer, the existence of a dead-letter topic - and two of the three had
 * a defect that loses data:
 *
 * <ul>
 *   <li><b>user-settings</b> ran {@code AckMode.MANUAL} with no error handler at all. A listener that
 *       threw before acknowledging never acknowledged, so the record was redelivered - forever, with no
 *       backoff, at full speed.</li>
 *   <li><b>identity-projection</b> declared no container factory, so it inherited Boot's default
 *       {@code DefaultErrorHandler}: ten attempts with no backoff, and then <em>log and move on</em>. The
 *       poison record was dropped and the only trace was a log line in a service nobody tails.</li>
 * </ul>
 *
 * <p>{@code NotificationMessagingConfig} was the third, and it was the one that had been thought through,
 * so its decisions and its reasoning are what this class carries. Its numbers are on
 * {@link ResolvedConsumerSettings} as named constants; its comments are on the methods that now make
 * those decisions.
 *
 * <h2>Typed and name-qualified, not wildcarded and cast</h2>
 *
 * <p>{@code UserSettingsKafkaAutoConfiguration} took a {@code ConsumerFactory<?, ?>} and cast it, with a
 * comment explaining that asking for {@code ConsumerFactory<String, String>} "looks tidier and does not
 * work" because a service declaring its own differently-typed factory leaves no bean matching that
 * signature. The comment was right about the problem and the cast was the wrong fix - it makes the
 * compiler's check disappear rather than satisfying it.
 *
 * <p>The fix is that a consumer no longer <em>injects</em> a factory at all. It asks this builder for one
 * at the exact type its payload is, and registers it under its own bean name. No bean of any shared
 * signature is looked up, so there is nothing for a differently-typed factory to collide with, and no
 * cast because the type is constructed rather than asserted.
 */
@Slf4j
public class ListenerContainerFactoryBuilder {

    private final KafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;
    private final MessagingProperties properties;
    private final MessagingMetrics metrics;
    private final ConsumerActivityMonitor activityMonitor;
    private final EnvelopeReader envelopeReader;
    private final AuditSink auditSink;
    private final Clock clock;
    private final KafkaTemplate<Object, Object> deadLetterTemplate;
    private final ObjectProvider<RecordFilterStrategy<Object, Object>> recordFilterStrategy;
    private final ObjectProvider<RecordInterceptor<Object, Object>> recordInterceptors;
    private final ObjectProvider<PlatformTransactionManager> transactionManager;
    private final ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry;
    private final boolean dedupWantsContainerTransaction;

    /**
     * Creates the builder. Assembled once, by {@code MessagingAutoConfiguration}.
     *
     * @param kafkaProperties                Boot's Kafka configuration, so bootstrap servers, security and
     *                                       group settings come from where every other consumer's do
     * @param objectMapper                   the application's mapper
     * @param properties                     this module's configuration
     * @param metrics                        where the counters and the silence gauge go
     * @param activityMonitor                what notices a topic has gone quiet
     * @param envelopeReader                 reads the envelope for the audit event
     * @param auditSink                      where a dead-lettered record is recorded
     * @param clock                          injected so a test can assert the recorded instant
     * @param recordFilterStrategy           the consumer-side dedup filter, when a deployment has one
     * @param recordInterceptors             every interceptor in the context - the observability module's
     *                                       correlation interceptor above all - composed rather than
     *                                       replaced
     * @param transactionManager             for a dedup claim that has to commit with the listener's work
     * @param meterRegistry                  for the Kafka client's own metrics, consumer lag included
     * @param dedupWantsContainerTransaction whether a transaction manager should be attached when a dedup
     *                                       filter is; resolved once by the autoconfiguration, which can
     *                                       see the idempotency module's claim mode
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a collaborator list, assembled once by the autoconfiguration.
    // Grouping these into a holder object would only move the same list one file away.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ListenerContainerFactoryBuilder(
            KafkaProperties kafkaProperties, ObjectMapper objectMapper, MessagingProperties properties,
            MessagingMetrics metrics, ConsumerActivityMonitor activityMonitor, EnvelopeReader envelopeReader,
            AuditSink auditSink, Clock clock,
            ObjectProvider<RecordFilterStrategy<Object, Object>> recordFilterStrategy,
            ObjectProvider<RecordInterceptor<Object, Object>> recordInterceptors,
            ObjectProvider<PlatformTransactionManager> transactionManager,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry,
            boolean dedupWantsContainerTransaction) {
        this.kafkaProperties = kafkaProperties;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metrics = metrics;
        this.activityMonitor = activityMonitor;
        this.envelopeReader = envelopeReader;
        this.auditSink = auditSink;
        this.clock = clock;
        // Built here rather than published as a bean, and that is the same lesson the Clock taught: a module
        // that adds a KafkaTemplate to the context makes every raw or Object-typed KafkaTemplate injection in
        // the application ambiguous. Nobody outside this class needs it, so nobody outside this class sees
        // it. A deployment that needs a different dead-letter producer replaces this whole builder, which is
        // @ConditionalOnMissingBean.
        this.deadLetterTemplate = DeadLetterTemplates.create(kafkaProperties, objectMapper);
        this.recordFilterStrategy = recordFilterStrategy;
        this.recordInterceptors = recordInterceptors;
        this.transactionManager = transactionManager;
        this.meterRegistry = meterRegistry;
        this.dedupWantsContainerTransaction = dedupWantsContainerTransaction;
    }

    /**
     * A factory for a consumer whose payload is JSON deserialized into one type.
     *
     * @param name        the consumer's name, which is the key its overrides live under in
     *                    {@code ludwig.messaging.consumers.*} and the tag its metrics carry
     * @param payloadType the payload type, fixed here rather than taken from a record header - see
     *                    {@link PayloadDeserializers}
     * @param topics      the topics this consumer reads, needed only for the silence signal; a consumer
     *                    that passes none simply has no silence gauge
     * @param <V>         the payload type
     * @return the factory, ready to register as a bean
     */
    public <V> ConcurrentKafkaListenerContainerFactory<String, V> forJsonPayload(
            String name, Class<V> payloadType, String... topics) {
        return build(name, gate(name, PayloadDeserializers.json(payloadType, objectMapper)), topics);
    }

    /**
     * A factory for a consumer that takes its payload as text and parses it itself.
     *
     * <p>Kept as a first-class option rather than treated as a legacy shape, because it is the right shape
     * for a consumer that dispatches on {@code ludwig-event-type} across several payload types on one
     * topic - which is what {@code SettingsEventListener} does. The shared error handling, the dead-letter
     * topic and the version gate are identical either way; what a text payload gives up is the container
     * refusing a malformed payload before the listener sees it.
     *
     * @param name   the consumer's name
     * @param topics the topics this consumer reads
     * @return the factory
     */
    public ConcurrentKafkaListenerContainerFactory<String, String> forTextPayload(
            String name, String... topics) {
        return build(name, gate(name, new StringDeserializer()), topics);
    }

    /**
     * Wraps a payload deserializer in the version gate, when the consumer declared a version bound.
     *
     * <p>No gate at all when it did not, rather than a gate with an unbounded range: an unbounded gate
     * would still read and parse the header on every record for no decision.
     */
    private <V> Deserializer<V> gate(String name, Deserializer<V> payload) {
        ResolvedConsumerSettings settings = properties.resolve(name);
        if (!settings.gatesEventVersion()) {
            return payload;
        }
        return new VersionGatingDeserializer<>(
                payload, settings.minEventVersion(), settings.maxEventVersion());
    }

    private <V> ConcurrentKafkaListenerContainerFactory<String, V> build(
            String name, Deserializer<V> payload, String... topics) {
        ResolvedConsumerSettings settings = properties.resolve(name);
        ConcurrentKafkaListenerContainerFactory<String, V> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory(payload));
        factory.getContainerProperties().setAckMode(settings.ackMode());
        if (settings.concurrency() != null) {
            factory.setConcurrency(settings.concurrency());
        }
        factory.setCommonErrorHandler(errorHandler(name, settings));
        factory.setRecordInterceptor(this.<V>interceptor());
        applyDedup(name, settings, factory);
        for (String topic : topics) {
            activityMonitor.watch(topic, settings.silenceThreshold());
        }
        log.debug("Built listener container factory '{}': ackMode={}, attempts={}, dlt={}, versions={}..{}",
                name, settings.ackMode(), settings.maxAttempts(),
                settings.deadLetterEnabled() ? settings.deadLetterSuffix() : "off",
                settings.minEventVersion(), settings.maxEventVersion());
        return factory;
    }

    /**
     * The consumer factory: Boot's own consumer properties, with the two deserializers replaced.
     *
     * <p>{@code ErrorHandlingDeserializer} wraps <em>both</em> key and value. That turns a malformed
     * payload into a record the error handler can classify and dead-letter. Without it a deserialization
     * exception is thrown before the listener is reached, the container has nothing to recover, and it
     * retries the same unparseable bytes forever - the classic poison-pill stall, which is precisely what
     * user-settings and identity-projection avoided only by taking their payloads as {@code String} and
     * therefore never failing to deserialize anything.
     *
     * <p>Auto-commit is off unconditionally. Offsets are committed by the container after the listener
     * returns, never by the broker on a timer: with auto-commit, a database outage advances the offset
     * past every record that arrived during it, and those records are gone. That is the failure that looks
     * like "the notifications from Tuesday afternoon never arrived" and has no trace anywhere.
     */
    private <V> DefaultKafkaConsumerFactory<String, V> consumerFactory(Deserializer<V> payload) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        DefaultKafkaConsumerFactory<String, V> consumerFactory = new DefaultKafkaConsumerFactory<>(config,
                new ErrorHandlingDeserializer<>(PayloadDeserializers.key()),
                new ErrorHandlingDeserializer<>(payload));
        if (properties.getMetrics().isEnabled()) {
            // The Kafka client's own metrics, consumer lag among them. Nothing in this platform bound
            // these before, so a platform with an observability starter had no lag metric at all - and
            // lag is the number every runbook for a Kafka consumer starts with.
            meterRegistry.ifAvailable(registry ->
                    consumerFactory.addListener(new MicrometerConsumerListener<>(registry)));
        }
        return consumerFactory;
    }

    /**
     * Retry with exponential backoff, then dead-letter.
     *
     * <p>The backoff is in the <em>container</em> rather than in the listener, so a failing record does not
     * occupy a thread while it waits - and so the whole partition pauses, which is what keeps a retried
     * record in order relative to the ones behind it. That head-of-line blocking is a feature here, not a
     * cost: see {@link ResolvedConsumerSettings#nonBlocking()} for what giving it up costs.
     *
     * <p>Retrying forever is the other way to lose a topic: one permanently unprocessable record blocks
     * its partition and every producer behind it stops. The dead-letter topic keeps the record for
     * inspection and lets the partition move on.
     */
    private DefaultErrorHandler errorHandler(String name, ResolvedConsumerSettings settings) {
        ExponentialBackOff backOff = new ExponentialBackOff(
                settings.initialBackoff().toMillis(), settings.multiplier());
        backOff.setMaxInterval(settings.maxBackoff().toMillis());
        // maxAttempts - 1, and the arithmetic is the point. Spring's ExponentialBackOff counts the intervals
        // it will hand out, which is the number of RETRIES; the first delivery has no interval before it. So
        // setMaxAttempts(4) produces five deliveries, not four.
        //
        // NotificationMessagingConfig set 4 under a comment reading "Attempts before a record is
        // dead-lettered, including the first" and therefore retried five times - the comment was the
        // intent and the code did something else, which nobody had measured because nothing counted the
        // deliveries. This module's property means what that comment said, and a test counts them.
        backOff.setMaxAttempts(settings.maxAttempts() - 1);

        DefaultErrorHandler handler = settings.deadLetterEnabled()
                ? new DefaultErrorHandler(recoverer(name, settings), backOff)
                : new DefaultErrorHandler(backOff);
        // A payload that could not be deserialized will never deserialize, so it goes straight to the
        // dead-letter topic instead of spending four attempts proving it. An unsupported event version
        // reaches here as a DeserializationException too, and for the same reason: a version does not
        // change on redelivery.
        handler.addNotRetryableExceptions(
                org.springframework.kafka.support.serializer.DeserializationException.class,
                org.springframework.messaging.converter.MessageConversionException.class);
        handler.setRetryListeners((record, exception, deliveryAttempt) ->
                metrics.recordRetry(record.topic()));
        return handler;
    }

    /**
     * The dead-letter publisher, wrapped so the send is counted and audited.
     *
     * <p>On this module's own template rather than the application's, and that is not tidiness - it is the
     * difference between a dead-letter topic that receives poison records and one that silently drops them.
     * See {@link ru.ludwigandreas.messaging.dlt.DeadLetterTemplates}.
     */
    private ConsumerRecordRecoverer recoverer(String name, ResolvedConsumerSettings settings) {
        DeadLetterPublishingRecoverer publishing = new DeadLetterPublishingRecoverer(deadLetterTemplate,
                (record, exception) -> new TopicPartition(
                        DeadLetterTopics.forTopic(record.topic(), settings.deadLetterSuffix()),
                        // -1 lets the broker choose the partition. Preserving the source partition would
                        // require the dead-letter topic to have at least as many, which nothing enforces -
                        // and a send to a partition that does not exist fails silently.
                        DeadLetterTopics.BROKER_CHOOSES_PARTITION));
        return new AuditingDeadLetterRecoverer(publishing, envelopeReader, metrics, auditSink, clock,
                settings.deadLetterSuffix(), settings.maxAttempts());
    }

    /**
     * Every {@code RecordInterceptor} in the context, plus this module's activity monitor.
     *
     * <p>Composed rather than assigned, and that is load-bearing.
     * {@code ObservabilityKafkaAutoConfiguration} contributes one interceptor, which restores the
     * producer's correlation id on the listener thread - and Boot applies it only to the container factory
     * Boot itself auto-configures. A factory built here that set its own interceptor would silently drop
     * the correlation id for every consumer this module configures, which is the one thing that makes a
     * producer-side and a consumer-side log line about the same event joinable.
     *
     * <p>{@code NotificationMessagingConfig} had this bug, latently: its own factory set no interceptor, so
     * {@code NotificationRequestListener}'s {@code correlationContext.currentId()} found nothing and every
     * request consumed from the topic was logged under a correlation id of its own. The listener's code was
     * correct and its comment described what was supposed to happen; nothing had connected the two. Building
     * the factory here composes the interceptor in, which makes that comment true.
     *
     * <p>The cast is to the factory's own key and value types. Every interceptor in this platform is
     * declared over {@code Object, Object} - spring-kafka's own extension point is written that way, and
     * Boot resolves exactly that signature - and each of them reads a record's headers and coordinates and
     * returns the record it was given. What the cast asserts, and what a {@code RecordInterceptor} is
     * documented to allow, is that an interceptor could return a <em>different</em> record; one that
     * returned a differently-typed record would fail in the listener rather than here. That risk is the
     * reason this is confined to one method with this comment on it instead of being spread across every
     * caller, which is what {@code UserSettingsKafkaAutoConfiguration}'s cast was doing.
     */
    @SuppressWarnings("unchecked")
    private <V> RecordInterceptor<String, V> interceptor() {
        List<RecordInterceptor<Object, Object>> all = new ArrayList<>();
        all.add(activityMonitor);
        recordInterceptors.orderedStream()
                .filter(candidate -> candidate != activityMonitor)
                .forEach(all::add);
        RecordInterceptor<Object, Object> composed = all.size() == 1
                ? activityMonitor
                : new CompositeRecordInterceptor<>(all.toArray(new RecordInterceptor[0]));
        return (RecordInterceptor<String, V>) (RecordInterceptor<?, ?>) composed;
    }

    /**
     * Attaches the consumer-side dedup filter, and the transaction it has to commit in.
     *
     * <p>The filter is taken by its spring-kafka interface rather than by
     * {@code IdempotentRecordFilterStrategy}, so a service can substitute its own and so this module does
     * not need the idempotency starter at runtime. What the idempotency starter's presence buys is the
     * decision below it: whether the claim has to commit with the listener's work.
     *
     * <p>A {@code TRANSACTIONAL} claim on a container with no transaction manager commits outside the
     * listener's transaction, so the key stays reserved for work that rolled back - and Kafka's
     * redelivery of that record, which is certain because the offset was not committed either, is then
     * discarded as a duplicate. The message is never delivered and nothing says so. That is why the
     * transaction manager is attached by default when a filter is present and a manager exists, and why
     * {@code MessagingConfigurationValidator} refuses a deployment that turns it off while the claim mode
     * is transactional.
     */
    private <V> void applyDedup(String name, ResolvedConsumerSettings settings,
                                ConcurrentKafkaListenerContainerFactory<String, V> factory) {
        if (!properties.getDedup().isEnabled() || !settings.dedup()) {
            return;
        }
        RecordFilterStrategy<Object, Object> filter = recordFilterStrategy.getIfAvailable();
        if (filter == null) {
            return;
        }
        // The cast is to the factory's own declared key/value types and is safe for the reason the
        // filter's own signature says: IdempotentRecordFilterStrategy is declared over Object, Object
        // because it inspects a record's coordinates and headers and never its deserialized value.
        @SuppressWarnings("unchecked")
        RecordFilterStrategy<String, V> typed = (RecordFilterStrategy<String, V>) (RecordFilterStrategy<?, ?>) filter;
        factory.setRecordFilterStrategy(typed);
        if (!dedupWantsContainerTransaction) {
            return;
        }
        PlatformTransactionManager manager = transactionManager.getIfAvailable();
        if (manager == null) {
            log.warn("Consumer '{}' has consumer-side dedup but the application has no transaction manager;"
                    + " a transactional claim cannot commit with the listener's work", name);
            return;
        }
        factory.getContainerProperties().setTransactionManager(manager);
    }
}
