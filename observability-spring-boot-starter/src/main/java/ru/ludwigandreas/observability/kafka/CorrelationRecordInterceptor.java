package ru.ludwigandreas.observability.kafka;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.listener.RecordInterceptor;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.observability.correlation.CorrelationIdResolver;

/**
 * Restores the producer's correlation id on the consumer thread, so a message handled here logs
 * under the same id as the request that published it.
 *
 * <p>This is the half of asynchronous correlation that tracing does not cover. Spring Kafka's
 * observation support links producer and consumer spans through the {@code traceparent} header,
 * which is exactly what is wanted - when the trace was sampled. At a realistic sampling probability
 * most messages carry a sampled-out trace context and produce no linkable span at all, whereas the
 * logs are written for every message. Without this interceptor those consumer-side log lines carry
 * no id of any kind, and the one query that should span producer and consumer returns only the
 * producer's half.
 *
 * <p>A record with no usable correlation header still gets an id, generated here. A consumer is an
 * entry point in its own right - a message may have been produced by a system that knows nothing of
 * this convention, or replayed from a topic long after its origin - and a batch of log lines with no
 * id cannot be told apart from the neighbouring message's.
 *
 * <h2>Thread-state handling</h2>
 *
 * <p>The scope opened in {@link #intercept} is closed in {@link #afterRecord}, which Spring Kafka
 * guarantees to call for every intercepted record, including one whose listener threw. Listener
 * threads are long-lived and handle every record for their partitions, so a scope left open would
 * not leak briefly - it would mislabel every subsequent message on that thread for the lifetime of
 * the container.
 */
public class CorrelationRecordInterceptor implements RecordInterceptor<Object, Object> {

    /**
     * Holds the open scope between the two callbacks.
     *
     * <p>Safe because both are invoked on the same listener thread for the same record, and because
     * Spring Kafka processes records on that thread one at a time - the interceptor is never
     * re-entered before {@code afterRecord} has run.
     */
    private final ThreadLocal<CorrelationContext.Scope> openScope = new ThreadLocal<>();

    private final CorrelationContext correlationContext;
    private final CorrelationIdResolver resolver;
    private final String headerName;

    public CorrelationRecordInterceptor(CorrelationContext correlationContext, CorrelationIdResolver resolver,
            String headerName) {
        this.correlationContext = correlationContext;
        this.resolver = resolver;
        this.headerName = headerName;
    }

    @Override
    public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record,
                                                    Consumer<Object, Object> consumer) {
        // The inbound value is validated by the same resolver the HTTP filter uses: a record header is
        // no more trustworthy than a request header, and a topic can be produced to by anything.
        String correlationId = resolver.resolve(java.util.List.of(headerValue(record)), () -> null);
        openScope.set(correlationContext.open(correlationId));
        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
        CorrelationContext.Scope scope = openScope.get();
        if (scope != null) {
            scope.close();
            openScope.remove();
        }
    }

    /** The header value as a string, or an empty string when absent - never null, so the resolver can skip it. */
    private String headerValue(ConsumerRecord<Object, Object> record) {
        Header header = record.headers().lastHeader(headerName);
        if (header == null || header.value() == null) {
            return "";
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
