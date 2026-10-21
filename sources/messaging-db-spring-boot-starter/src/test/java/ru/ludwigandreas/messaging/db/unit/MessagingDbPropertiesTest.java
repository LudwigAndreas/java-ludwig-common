package ru.ludwigandreas.messaging.db.unit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ru.ludwigandreas.messaging.db.settings.MessagingDbProperties;

class MessagingDbPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void theDefaultsAreActiveAndValid() {
        MessagingDbProperties properties = bind(Map.of());

        assertThat(properties.getMode()).isEqualTo(MessagingDbProperties.Mode.ACTIVE);
        assertThat(properties.getConsume()).isEmpty();
        assertThat(properties.getPartitions()).isEqualTo(8);
        assertThat(properties.getPollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.getSchema()).isEqualTo("ludwig_messaging");
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void topicsAndModeBindFromConfiguration() {
        MessagingDbProperties properties = bind(Map.of(
                "ludwig.messaging.db.mode", "draining",
                "ludwig.messaging.db.consume", "a.b,c.d"));

        assertThat(properties.getMode()).isEqualTo(MessagingDbProperties.Mode.DRAINING);
        assertThat(properties.getConsume()).containsExactly("a.b", "c.d");
    }

    @Test
    void anUnsafeSchemaAndAZeroPartitionCountAreRejected() {
        Set<ConstraintViolation<MessagingDbProperties>> violations = validator.validate(bind(Map.of(
                "ludwig.messaging.db.schema", "Public; DROP",
                "ludwig.messaging.db.partitions", "0")));

        assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("schema", "partitions");
    }

    private static MessagingDbProperties bind(Map<String, String> config) {
        return new Binder(new MapConfigurationPropertySource(config))
                .bindOrCreate(MessagingDbProperties.PREFIX, MessagingDbProperties.class);
    }
}
