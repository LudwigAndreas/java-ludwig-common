package ru.ludwigandreas.messaging.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ru.ludwigandreas.messaging.settings.KafkaClusters;

class KafkaClustersTest {

    @Test
    @DisplayName("with no cluster declared there is exactly the default one, which is spring.kafka itself")
    void defaultIsSpringKafka() {
        KafkaProperties defaults = new KafkaProperties();
        KafkaClusters clusters = KafkaClusters.bind(binder(Map.of()), defaults);

        assertThat(clusters.names()).containsExactly(KafkaClusters.DEFAULT);
        assertThat(clusters.properties(KafkaClusters.DEFAULT)).isSameAs(defaults);
    }

    @Test
    @DisplayName("a cluster inherits spring.kafka and overrides only what it states")
    void inheritsAndOverrides() {
        KafkaClusters clusters = KafkaClusters.bind(binder(Map.of(
                "spring.kafka.bootstrap-servers", "default:9092",
                "spring.kafka.consumer.auto-offset-reset", "earliest",
                "spring.kafka.producer.acks", "all",
                "ludwig.kafka.clusters.partner-a.bootstrap-servers", "a:9093",
                "ludwig.kafka.clusters.partner-a.security.protocol", "SSL")), new KafkaProperties());

        KafkaProperties partner = clusters.properties("partner-a");
        assertThat(partner.buildConsumerProperties(null))
                .containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, java.util.List.of("a:9093"))
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .containsEntry("security.protocol", "SSL");
        assertThat(partner.buildProducerProperties(null)).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
    }

    @Test
    @DisplayName("an undeclared cluster is refused, naming it and the declared ones")
    void refusesAnUndeclaredCluster() {
        KafkaClusters clusters = KafkaClusters.bind(binder(Map.of(
                "ludwig.kafka.clusters.partner-a.bootstrap-servers", "a:9093")), new KafkaProperties());

        assertThatThrownBy(() -> clusters.properties("partner-b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partner-b").hasMessageContaining("partner-a");
    }

    @Test
    @DisplayName("'default' cannot be redeclared, and a name must fit an outbox transport")
    void refusesReservedAndOversizedNames() {
        assertThatThrownBy(() -> KafkaClusters.bind(binder(Map.of(
                "ludwig.kafka.clusters.default.bootstrap-servers", "x:1")), new KafkaProperties()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("spring.kafka");
        assertThatThrownBy(() -> KafkaClusters.bind(binder(Map.of(
                "ludwig.kafka.clusters." + "a".repeat(45) + ".bootstrap-servers", "x:1")), new KafkaProperties()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("44");
    }

    private static Binder binder(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties));
    }
}
