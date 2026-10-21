package ru.ludwigandreas.outbox.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ru.ludwigandreas.messaging.settings.KafkaClusters;
import ru.ludwigandreas.outbox.config.OutboxKafkaAutoConfiguration;
import ru.ludwigandreas.outbox.config.OutboxProperties;
import ru.ludwigandreas.outbox.dispatch.DispatchResult;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcher;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcherRegistry;
import ru.ludwigandreas.outbox.entity.OutboxMessage;

class OutboxDispatcherRegistryTest {

    @Test
    void groupDispatchersAreFoundByTransportCaseInsensitively() {
        OutboxDispatcherRegistry registry = new OutboxDispatcherRegistry(List.of(dispatcher("KAFKA")),
                List.of(() -> List.of(dispatcher("KAFKA:PARTNER-A"))));

        assertThat(registry.find("kafka")).isPresent();
        assertThat(registry.find("kafka:partner-a")).isPresent();
        assertThat(registry.find("kafka:partner-b")).isEmpty();
    }

    @Test
    void aTransportContributedTwiceIsRefused() {
        assertThatThrownBy(() -> new OutboxDispatcherRegistry(List.of(dispatcher("KAFKA:PARTNER-A")),
                List.of(() -> List.of(dispatcher("kafka:partner-a")))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRouteNamingAnUndeclaredClusterIsRefusedAtStartup() {
        OutboxProperties properties = new OutboxProperties();
        OutboxProperties.Route route = new OutboxProperties.Route();
        route.setTransport("kafka:partner-c");
        route.setDestination("invoices");
        properties.getRoutes().put("invoices", route);
        KafkaClusters clusters = KafkaClusters.bind(new Binder(new MapConfigurationPropertySource(Map.of(
                "ludwig.kafka.clusters.partner-a.bootstrap-servers", "a:9093"))), new KafkaProperties());

        assertThatThrownBy(() -> new OutboxKafkaAutoConfiguration().outboxKafkaRouteValidator(properties, clusters)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invoices").hasMessageContaining("partner-c");
    }

    private static OutboxDispatcher dispatcher(String transport) {
        return new OutboxDispatcher() {
            @Override
            public String transport() {
                return transport;
            }

            @Override
            public DispatchResult dispatch(OutboxMessage message) {
                return DispatchResult.success();
            }
        };
    }
}
