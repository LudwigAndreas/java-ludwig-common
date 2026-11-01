package ru.ludwigandreas.identity.kafka.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.converter.MessageConversionException;
import ru.ludwigandreas.identity.feed.ApplyOutcome;
import ru.ludwigandreas.identity.feed.IdentityChange;
import ru.ludwigandreas.identity.feed.IdentityProjection;
import ru.ludwigandreas.identity.feed.IdentitySweep;
import ru.ludwigandreas.identity.kafka.IdentityKafkaSubscriptions;
import ru.ludwigandreas.identity.kafka.IdentityUserEventListener;
import ru.ludwigandreas.identity.kafka.IdentityUserEventParser;
import ru.ludwigandreas.identity.kafka.config.IdentityKafkaProperties;

class IdentityUserEventListenerTest {

    private final List<IdentityChange> applied = new ArrayList<>();

    private final IdentityProjection projection = new IdentityProjection() {
        @Override
        public ApplyOutcome apply(IdentityChange change) {
            applied.add(change);
            return ApplyOutcome.APPLIED;
        }

        @Override
        public List<ApplyOutcome> applyAll(List<? extends IdentityChange> changes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IdentitySweep beginSweep(String source) {
            throw new UnsupportedOperationException();
        }
    };

    private static final IdentityUserEventParser PARSER =
            new IdentityUserEventParser(new ObjectMapper().registerModule(new JavaTimeModule()));

    private final IdentityUserEventListener listener = new IdentityUserEventListener(projection,
            new IdentityKafkaSubscriptions(properties()), PARSER);

    private static IdentityKafkaProperties properties() {
        IdentityKafkaProperties properties = new IdentityKafkaProperties();
        properties.setGroupId("svc-identity");
        properties.getSubscriptions().put("staff", subscription("corp.staff.users"));
        properties.getSubscriptions().put("customers", subscription("shop.customer.users"));
        return properties;
    }

    private static IdentityKafkaProperties.Subscription subscription(String topic) {
        IdentityKafkaProperties.Subscription subscription = new IdentityKafkaProperties.Subscription();
        subscription.setTopic(topic);
        return subscription;
    }

    private static ConsumerRecord<String, String> record(String topic, String value) {
        return new ConsumerRecord<>(topic, 0, 0L, "key", value);
    }

    private static final String UPSERT = "{\"eventId\":\"e1\",\"type\":\"UPSERT\",\"subject\":\"u-1\","
            + "\"displayName\":\"Anna\",\"tenantId\":\"t-1\",\"roles\":[\"ORDER_AGENT\"],\"email\":\"a@x.example\","
            + "\"emailVerified\":true,\"department\":\"sales\",\"occurredAt\":\"2026-03-01T10:00:00Z\"}";

    @Test
    @DisplayName("each topic feeds its own source, and a field the contract does not name is ignored")
    void topicSelectsTheSource() {
        listener.onMessage(record("corp.staff.users", UPSERT));
        listener.onMessage(record("shop.customer.users", UPSERT));

        assertThat(applied).extracting(IdentityChange::source).containsExactly("staff", "customers");
        IdentityChange.Upsert upsert = (IdentityChange.Upsert) applied.get(0);
        assertThat(upsert.subject()).isEqualTo("u-1");
        assertThat(upsert.occurredAt()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
        assertThat(upsert.profile().roles()).containsExactly("ORDER_AGENT");
        assertThat(upsert.profile().contact().email()).isEqualTo("a@x.example");
    }

    @Test
    @DisplayName("DELETE is applied as a remove, DISABLE as a disable")
    void mapsTheTypes() {
        listener.onMessage(record("corp.staff.users",
                "{\"type\":\"DELETE\",\"subject\":\"u-1\",\"occurredAt\":\"2026-03-01T10:00:00Z\"}"));
        listener.onMessage(record("corp.staff.users",
                "{\"type\":\"DISABLE\",\"subject\":\"u-2\",\"occurredAt\":\"2026-03-01T10:00:00Z\"}"));

        assertThat(applied.get(0)).isInstanceOf(IdentityChange.Remove.class);
        assertThat(applied.get(1)).isInstanceOf(IdentityChange.Disable.class);
    }

    @Test
    @DisplayName("a payload that is not an event of the contract is a non-retryable conversion failure")
    void malformedIsNonRetryable() {
        assertThatThrownBy(() -> listener.onMessage(record("corp.staff.users", "not json")))
                .isInstanceOf(MessageConversionException.class);
        assertThatThrownBy(() -> listener.onMessage(record("corp.staff.users", "{\"type\":\"UPSERT\"}")))
                .isInstanceOf(MessageConversionException.class).hasMessageContaining("occurredAt");
        assertThatThrownBy(() -> listener.onMessage(record("corp.staff.users",
                "{\"type\":\"RENAME\",\"occurredAt\":\"2026-03-01T10:00:00Z\"}")))
                .isInstanceOf(MessageConversionException.class);
        assertThat(applied).isEmpty();
    }

    @Test
    @DisplayName("a failure while applying is rethrown as it is, so the consumer retries it")
    void applyFailureIsRetried() {
        IdentityUserEventListener failing = new IdentityUserEventListener(new IdentityProjection() {
            @Override
            public ApplyOutcome apply(IdentityChange change) {
                throw new IllegalStateException("database down");
            }

            @Override
            public List<ApplyOutcome> applyAll(List<? extends IdentityChange> changes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public IdentitySweep beginSweep(String source) {
                throw new UnsupportedOperationException();
            }
        }, new IdentityKafkaSubscriptions(properties()), PARSER);

        assertThatThrownBy(() -> failing.onMessage(record("corp.staff.users", UPSERT)))
                .isInstanceOf(IllegalStateException.class).isNotInstanceOf(MessageConversionException.class);
    }
}
