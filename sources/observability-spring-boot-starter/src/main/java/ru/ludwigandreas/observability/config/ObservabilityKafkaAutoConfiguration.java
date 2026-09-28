package ru.ludwigandreas.observability.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.RecordInterceptor;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.observability.correlation.CorrelationIdResolver;
import ru.ludwigandreas.observability.kafka.CorrelationProducerPostProcessor;
import ru.ludwigandreas.observability.kafka.CorrelationRecordInterceptor;

/**
 * Carries the correlation id across the broker, in both directions.
 *
 * <p>Trace context already crosses it - Spring Kafka's observation support writes {@code traceparent}
 * onto the record and resumes the trace in the consumer, and this module's environment defaults turn
 * that support on, since it ships disabled. What tracing cannot do is cover the unsampled majority
 * of messages, and those still produce logs. Without the correlation id, a message's producer-side
 * and consumer-side log lines share nothing, and the most common question about an asynchronous
 * system - "what happened to this one event?" - has no answer.
 *
 * <p>Both beans are ordinary Spring beans that Spring Kafka and Spring Boot pick up through
 * documented extension points, so neither replaces anything an application has configured. See
 * {@link CorrelationProducerPostProcessor} for why the producer is wrapped at the factory rather
 * than through {@code KafkaTemplate}'s interceptor setter.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnClass({KafkaTemplate.class, RecordInterceptor.class})
@ConditionalOnProperty(
        name = {"ludwig.observability.enabled", "ludwig.observability.correlation.enabled",
                "ludwig.observability.correlation.kafka.enabled"},
        matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityKafkaAutoConfiguration {

    /**
     * Restores the correlation id on the listener thread.
     *
     * <p>Spring Boot's {@code KafkaAnnotationDrivenConfiguration} takes a single
     * {@code RecordInterceptor<Object, Object>} bean and applies it to the auto-configured listener
     * container factory, so declaring one here is all that is required.
     *
     * <p>{@code @ConditionalOnMissingBean} on the interface, not on the bean name: Boot resolves that
     * interceptor with {@code getIfUnique}, so a second bean of this type would leave it with none
     * and silently disable <em>both</em> this interceptor and the application's. Backing off entirely
     * when the application declares its own keeps the application's working - it can delegate to
     * {@link CorrelationContext} directly if it wants both behaviours.
     */
    @Bean
    @ConditionalOnMissingBean(RecordInterceptor.class)
    public RecordInterceptor<Object, Object> ludwigCorrelationRecordInterceptor(
            CorrelationContext correlationContext, CorrelationIdResolver resolver,
            ObservabilityProperties properties) {
        return new CorrelationRecordInterceptor(
                correlationContext, resolver, properties.getCorrelation().getKafka().getHeaderName());
    }

    /**
     * Stamps the correlation id onto every record produced through the Spring-managed producer
     * factory.
     *
     * <p>{@code DefaultKafkaProducerFactoryCustomizer} is Boot's own extension point for exactly this
     * and composes with any other customizer, and {@code addPostProcessor} appends rather than
     * assigns - so nothing already configured on the factory is displaced.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ludwigCorrelationProducerFactoryCustomizer")
    public DefaultKafkaProducerFactoryCustomizer ludwigCorrelationProducerFactoryCustomizer(
            CorrelationContext correlationContext, ObservabilityProperties properties) {
        String headerName = properties.getCorrelation().getKafka().getHeaderName();
        return producerFactory -> addPostProcessor(producerFactory, correlationContext, headerName);
    }

    /**
     * Attaches the post-processor to a factory whose key and value types are wildcards.
     *
     * <p>The indirection through a generic method is what keeps this type-safe. {@code customize}
     * receives a {@code DefaultKafkaProducerFactory<?, ?>}, and {@code addPostProcessor} demands a
     * post-processor matching those captured types - which no expression written inline can name.
     * Passing the factory to a generic method applies capture conversion, binding {@code K} and
     * {@code V} to the captures for the duration of the call, so the post-processor can be
     * constructed at exactly the right type. The alternative is a raw-type cast, which compiles by
     * discarding the very check that makes this correct.
     */
    private static <K, V> void addPostProcessor(DefaultKafkaProducerFactory<K, V> producerFactory,
            CorrelationContext correlationContext, String headerName) {
        producerFactory.addPostProcessor(new CorrelationProducerPostProcessor<>(correlationContext, headerName));
    }
}
