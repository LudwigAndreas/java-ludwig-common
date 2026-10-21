package ru.ludwigandreas.messaging.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.adapter.RecordFilterStrategy;
import ru.ludwigandreas.idempotency.api.ClaimMode;
import ru.ludwigandreas.idempotency.api.ClaimRequest;
import ru.ludwigandreas.idempotency.api.ClaimResult;
import ru.ludwigandreas.idempotency.api.IdempotencyStore;
import ru.ludwigandreas.idempotency.api.RequestFingerprint;
import ru.ludwigandreas.idempotency.api.StoredResponse;
import ru.ludwigandreas.idempotency.config.IdempotencyProperties;
import ru.ludwigandreas.idempotency.kafka.IdempotentRecordFilterStrategy;
import ru.ludwigandreas.idempotency.metrics.IdempotencyMetrics;
import ru.ludwigandreas.messaging.config.KafkaClustersAutoConfiguration;
import ru.ludwigandreas.messaging.config.MessagingAutoConfiguration;
import ru.ludwigandreas.messaging.config.MessagingMetricsAutoConfiguration;
import ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder;

/**
 * Consumers on two clusters, each written exactly as a single-cluster consumer is: the cluster is in
 * configuration, under the consumer's name, and nowhere in this class.
 */
@SpringBootConfiguration
@EnableKafka
@ImportAutoConfiguration({KafkaAutoConfiguration.class, KafkaClustersAutoConfiguration.class,
        MessagingMetricsAutoConfiguration.class, MessagingAutoConfiguration.class})
public class MultiClusterTestApplication {

    static final String ORDERS = "it.mc.orders";
    static final String FAILING = "it.mc.failing";
    static final String FAILING_ELSEWHERE = "it.mc.failing-elsewhere";

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    Received received() {
        return new Received();
    }

    @Bean
    Listeners listeners(Received received) {
        return new Listeners(received);
    }

    /** The idempotency starter's real filter over an in-memory store, so dedup runs with no database. */
    @Bean
    RecordFilterStrategy<Object, Object> dedupFilter() {
        return new IdempotentRecordFilterStrategy(new InMemoryStore(), new RequestFingerprint(new ObjectMapper()),
                new IdempotencyProperties(), IdempotencyMetrics.NOOP);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> defaultOrdersFactory(ListenerContainerFactoryBuilder b) {
        return b.forTextPayload("default-orders", ORDERS);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> partnerOrdersFactory(ListenerContainerFactoryBuilder b) {
        return b.forTextPayload("partner-orders", ORDERS);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> partnerFailingFactory(ListenerContainerFactoryBuilder b) {
        return b.forTextPayload("partner-failing", FAILING);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> partnerFailingElsewhereFactory(
            ListenerContainerFactoryBuilder b) {
        return b.forTextPayload("partner-failing-elsewhere", FAILING_ELSEWHERE);
    }

    public static class Received {

        public final List<String> values = Collections.synchronizedList(new ArrayList<>());
    }

    public static class Listeners {

        private final Received received;

        Listeners(Received received) {
            this.received = received;
        }

        @KafkaListener(topics = ORDERS, groupId = "it-mc-default", containerFactory = "defaultOrdersFactory")
        public void defaultOrders(ConsumerRecord<String, String> record) {
            received.values.add("default:" + record.value());
        }

        @KafkaListener(topics = ORDERS, groupId = "it-mc-partner", containerFactory = "partnerOrdersFactory")
        public void partnerOrders(ConsumerRecord<String, String> record) {
            received.values.add("partner-a:" + record.value());
        }

        @KafkaListener(topics = FAILING, groupId = "it-mc-failing", containerFactory = "partnerFailingFactory")
        public void failing(String value) {
            throw new IllegalStateException("always fails");
        }

        @KafkaListener(topics = FAILING_ELSEWHERE, groupId = "it-mc-failing-elsewhere",
                containerFactory = "partnerFailingElsewhereFactory")
        public void failingElsewhere(String value) {
            throw new IllegalStateException("always fails");
        }
    }

    /** Claims keyed by scope and key; a second claim of the same pair loses. */
    static final class InMemoryStore implements IdempotencyStore {

        private final Map<String, UUID> claims = new ConcurrentHashMap<>();

        @Override
        public ClaimResult claim(ClaimRequest request) {
            UUID existing = claims.putIfAbsent(request.scope() + "|" + request.key(), request.requestId());
            if (existing == null) {
                return ClaimResult.claimed(request.requestId(), request.fingerprint(),
                        Instant.now().plus(request.ttl()));
            }
            return new ClaimResult(ru.ludwigandreas.idempotency.api.ClaimOutcome.COMPLETED, existing,
                    request.fingerprint(), null, Instant.now().plus(request.ttl()));
        }

        @Override
        public Optional<ClaimResult> find(String scope, String key) {
            return Optional.empty();
        }

        @Override
        public boolean complete(String scope, String key, UUID requestId, StoredResponse response) {
            return true;
        }

        @Override
        public boolean fail(String scope, String key, UUID requestId, String reason) {
            return true;
        }

        @Override
        public boolean renewLease(String scope, String key, UUID requestId, Duration lease) {
            return true;
        }

        @Override
        public long purgeExpired(Instant now) {
            return 0;
        }

        @Override
        public boolean supports(ClaimMode mode) {
            return true;
        }
    }
}
