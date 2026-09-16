package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.observability.correlation.CorrelationIdResolver;
import ru.ludwigandreas.observability.kafka.CorrelationProducerPostProcessor;
import ru.ludwigandreas.observability.kafka.CorrelationRecordInterceptor;

/** The hop tracing cannot cover on its own: the unsampled majority of messages crossing the broker. */
class KafkaCorrelationTest {

    private static final String HEADER = "X-Correlation-Id";
    private static final String TOPIC = "orders";

    private final CorrelationContext context = new CorrelationContext("correlationId");
    private final CorrelationIdResolver resolver = new CorrelationIdResolver("[A-Za-z0-9_.:-]+", 128, true);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void stampsTheCurrentCorrelationIdOntoAProducedRecord() {
        ProducerRecord<Object, Object> record = new ProducerRecord<>(TOPIC, "key", "value");

        try (CorrelationContext.Scope ignored = context.open("order-4711")) {
            sendThroughWrappedProducer(record);
        }

        assertThat(headerValue(record)).isEqualTo("order-4711");
    }

    @Test
    void leavesAHeaderTheProducerSetItselfAlone() {
        ProducerRecord<Object, Object> record = new ProducerRecord<>(TOPIC, "key", "value");
        record.headers().add(HEADER, "deliberately-forwarded".getBytes(StandardCharsets.UTF_8));

        try (CorrelationContext.Scope ignored = context.open("order-4711")) {
            sendThroughWrappedProducer(record);
        }

        // Code that set the header is forwarding an id from a source this wrapper cannot see;
        // replacing it would sever the very chain it exists to preserve.
        assertThat(headerValue(record)).isEqualTo("deliberately-forwarded");
    }

    @Test
    void addsNoHeaderWhenThereIsNoCorrelationIdInScope() {
        ProducerRecord<Object, Object> record = new ProducerRecord<>(TOPIC, "key", "value");

        sendThroughWrappedProducer(record);

        assertThat(record.headers().lastHeader(HEADER)).isNull();
    }

    @Test
    void stillDelegatesEveryOtherProducerMethodToTheRealProducer() {
        List<String> calls = new java.util.ArrayList<>();
        Producer<Object, Object> wrapped = wrap(fakeProducer(calls, null));

        wrapped.flush();
        wrapped.partitionsFor(TOPIC);

        // The wrapper is a dynamic proxy that only describes send(); everything else has to reach the
        // real producer untouched, including methods the Kafka client may add in a later version.
        assertThat(calls).containsExactly("flush", "partitionsFor");
    }

    @Test
    void unwrapsAProducerFailureSoCallerCatchBlocksStillMatch() {
        Producer<Object, Object> delegate = fakeProducer(new java.util.ArrayList<>(),
                new IllegalStateException("broker down"));

        // Without unwrapping, callers would see an UndeclaredThrowableException wrapping their
        // exception and every existing catch block would stop matching.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> wrap(delegate).flush())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("broker down");
    }

    @Test
    void restoresTheProducersCorrelationIdOnTheConsumerThread() {
        CorrelationRecordInterceptor interceptor = new CorrelationRecordInterceptor(context, resolver, HEADER);
        ConsumerRecord<Object, Object> record = consumerRecord("order-4711");

        interceptor.intercept(record, null);

        assertThat(MDC.get("correlationId")).isEqualTo("order-4711");

        interceptor.afterRecord(record, null);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void generatesAnIdForARecordProducedBySomethingThatKnowsNothingOfThisConvention() {
        CorrelationRecordInterceptor interceptor = new CorrelationRecordInterceptor(context, resolver, HEADER);

        interceptor.intercept(consumerRecord(null), null);

        // A batch of consumer log lines with no id at all cannot be told apart from the next message's.
        assertThat(MDC.get("correlationId")).hasSize(32);
    }

    @Test
    void validatesAnInboundRecordHeaderJustAsStrictlyAsAnHttpHeader() {
        CorrelationRecordInterceptor interceptor = new CorrelationRecordInterceptor(context, resolver, HEADER);

        interceptor.intercept(consumerRecord("evil\r\ninjected"), null);

        // A topic can be produced to by anything, so a record header is no more trustworthy than a
        // request header.
        assertThat(MDC.get("correlationId")).hasSize(32).doesNotContain("injected");
    }

    @Test
    void doesNotLeakAnIdIntoTheNextMessageHandledOnTheSameListenerThread() {
        CorrelationRecordInterceptor interceptor = new CorrelationRecordInterceptor(context, resolver, HEADER);

        ConsumerRecord<Object, Object> first = consumerRecord("first-id");
        interceptor.intercept(first, null);
        interceptor.afterRecord(first, null);

        ConsumerRecord<Object, Object> second = consumerRecord("second-id");
        interceptor.intercept(second, null);

        assertThat(MDC.get("correlationId")).isEqualTo("second-id");
    }

    private void sendThroughWrappedProducer(ProducerRecord<Object, Object> record) {
        wrap(fakeProducer(new java.util.ArrayList<>(), null)).send(record);
    }

    private Producer<Object, Object> wrap(Producer<Object, Object> delegate) {
        return new CorrelationProducerPostProcessor<Object, Object>(context, HEADER).apply(delegate);
    }

    /**
     * A recording stand-in for a Kafka producer, built as a dynamic proxy.
     *
     * <p>Not a Mockito mock: {@code Producer} extends {@code Closeable}, and the inline mock maker
     * cannot instrument {@code java.io.Closeable} on recent JDKs. A proxy needs no instrumentation,
     * records what was called, and returns null for every method - which is fine here because none
     * of {@code Producer}'s methods return a primitive.
     */
    @SuppressWarnings("unchecked")
    private Producer<Object, Object> fakeProducer(List<String> calls, RuntimeException failure) {
        return (Producer<Object, Object>) java.lang.reflect.Proxy.newProxyInstance(
                Producer.class.getClassLoader(),
                new Class<?>[] {Producer.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    if (failure != null) {
                        throw failure;
                    }
                    return null;
                });
    }

    private String headerValue(ProducerRecord<Object, Object> record) {
        Header header = record.headers().lastHeader(HEADER);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private ConsumerRecord<Object, Object> consumerRecord(String correlationId) {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>(TOPIC, 0, 0L, "key", "value");
        if (correlationId != null) {
            record.headers().add(HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Test
    void producesRecordsThroughAFactoryCustomizerWithoutDisplacingExistingPostProcessors() {
        // addPostProcessor appends rather than assigns, which is why the factory is the hook rather
        // than KafkaTemplate.setProducerInterceptor - see CorrelationProducerPostProcessor's javadoc.
        org.springframework.kafka.core.DefaultKafkaProducerFactory<Object, Object> factory =
                new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(java.util.Map.of());
        factory.addPostProcessor(producer -> producer);
        factory.addPostProcessor(new CorrelationProducerPostProcessor<>(context, HEADER));

        assertThat(factory.getPostProcessors()).hasSize(2);
    }
}
