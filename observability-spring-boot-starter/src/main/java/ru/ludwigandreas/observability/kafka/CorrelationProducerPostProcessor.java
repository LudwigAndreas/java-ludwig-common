package ru.ludwigandreas.observability.kafka;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.ProducerPostProcessor;
import ru.ludwigandreas.observability.correlation.CorrelationContext;

/**
 * Stamps the current correlation id onto every record a producer sends.
 *
 * <h2>Why the producer is wrapped rather than the template configured</h2>
 *
 * <p>{@code KafkaTemplate} has a {@code setProducerInterceptor} that would do this in one line, and
 * it is the wrong hook for a starter. It is a setter with no getter, so a library calling it
 * silently discards whatever interceptor the application had already installed, and there is no way
 * to detect that this has happened. It also only covers sends made through that one template -
 * missing anything that takes the {@code ProducerFactory} directly, which is what most batching and
 * transactional code does.
 *
 * <p>Wrapping at the factory avoids both problems. Post-processors are <em>added</em> to a list
 * rather than assigned, so several can coexist and this one cannot displace anything; and every
 * producer the factory hands out is wrapped, whoever asked for it.
 *
 * <p>The wrapper is a JDK dynamic proxy so that only {@code send} is described here and the other
 * twenty-odd {@code Producer} methods are not restated as pass-through delegates - a body of
 * boilerplate whose only possible contribution is a transcription error, and which would need
 * revisiting every time the Kafka client adds a method.
 *
 * <h2>Boundary</h2>
 *
 * <p>This covers producers obtained from the Spring-managed {@code DefaultKafkaProducerFactory}.
 * Code that constructs a {@code KafkaProducer} itself, bypassing the factory, is not intercepted and
 * cannot be: there is no hook to attach to. Such code should call
 * {@link CorrelationContext#currentId()} and set the header itself.
 */
public class CorrelationProducerPostProcessor<K, V> implements ProducerPostProcessor<K, V> {

    private final CorrelationContext correlationContext;
    private final String headerName;

    public CorrelationProducerPostProcessor(CorrelationContext correlationContext, String headerName) {
        this.correlationContext = correlationContext;
        this.headerName = headerName;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Producer<K, V> apply(Producer<K, V> producer) {
        return (Producer<K, V>) Proxy.newProxyInstance(
                Producer.class.getClassLoader(),
                new Class<?>[] {Producer.class},
                (proxy, method, args) -> {
                    if (isSend(method.getName(), args) && args[0] instanceof ProducerRecord<?, ?> record) {
                        addCorrelationHeader(record);
                    }
                    try {
                        return method.invoke(producer, args);
                    } catch (InvocationTargetException e) {
                        // Unwrap, or every producer error reaches callers as a reflection wrapper
                        // around the real exception - and their catch blocks stop matching.
                        throw e.getTargetException();
                    }
                });
    }

    private boolean isSend(String methodName, Object[] args) {
        return "send".equals(methodName) && args != null && args.length > 0;
    }

    /**
     * Adds the header unless the record already carries one.
     *
     * <p>Not overwritten, for the same reason as on the HTTP client side: a producer that set the
     * header itself is forwarding an id from somewhere this code cannot see - a consumed message, a
     * business process - and replacing it would break the chain it was recording.
     *
     * <p>Mutating the record in place is safe here. Headers become immutable only once the producer
     * has serialized the record, which happens inside the {@code send} call this proxy is standing
     * in front of.
     */
    private void addCorrelationHeader(ProducerRecord<?, ?> record) {
        if (record.headers().lastHeader(headerName) != null) {
            return;
        }
        correlationContext.currentId().ifPresent(id ->
                record.headers().add(headerName, id.getBytes(StandardCharsets.UTF_8)));
    }
}
