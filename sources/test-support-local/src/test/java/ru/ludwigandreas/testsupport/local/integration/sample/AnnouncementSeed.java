package ru.ludwigandreas.testsupport.local.integration.sample;

import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaOperations;
import ru.ludwigandreas.testsupport.local.fixture.KafkaDeliveries;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixture;

/** A fixture that publishes, so it says it needs a broker. */
public class AnnouncementSeed implements SeedFixture {

    /** The key of the one record this fixture publishes. */
    public static final String KEY = "announcement-seed";

    private final KafkaOperations<String, String> kafka;

    public AnnouncementSeed(KafkaOperations<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void seed() {
        KafkaDeliveries.publish(kafka, List.of(
                new ProducerRecord<>(SampleKafkaConfiguration.TOPIC, KEY, "widgets are seeded")));
    }

    @Override
    public boolean needsKafka() {
        return true;
    }
}
