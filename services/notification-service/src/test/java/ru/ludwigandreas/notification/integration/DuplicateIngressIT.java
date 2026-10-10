package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.ConfigurableApplicationContext;
import ru.ludwigandreas.notification.local.IngressSeed;
import ru.ludwigandreas.notification.messaging.NotificationRequestMessage;
import ru.ludwigandreas.testsupport.local.fixture.KafkaDeliveries;
import ru.ludwigandreas.testsupport.local.mail.LocalMailbox;

/**
 * The same request arrives on the ingress topic twice, and is sent once.
 *
 * <p>A Kafka consumer is at-least-once by construction, so a redelivery is the contract and not an
 * accident - and it is the case nobody can produce on demand against a healthy broker.
 * {@link KafkaDeliveries#duplicate} produces it: two records with the same key and payload at
 * different offsets, which is what the listener sees after a lost commit.
 *
 * <p>Run against the launched application, with the real listener, the real idempotency claim and
 * the real poller. The assertion is made at the far end, in the mailbox, because "one row" is an
 * implementation detail and "the customer got one email" is the point.
 */
class DuplicateIngressIT {

    private static final Duration DELIVERED_WITHIN = Duration.ofSeconds(60);
    private static final Duration STAYS_SINGLE_FOR = Duration.ofSeconds(8);

    private static ConfigurableApplicationContext application;

    @BeforeAll
    static void start() {
        application = LaunchedApplication.start();
    }

    @AfterAll
    static void stop() {
        if (application != null) {
            application.close();
        }
    }

    @Test
    @DisplayName("a request delivered twice to the ingress topic produces one email")
    void redeliveryIsSentOnce() throws Exception {
        String idempotencyKey = "duplicate-" + UUID.randomUUID();
        String address = idempotencyKey + "@notifications.local";
        NotificationRequestMessage message = new NotificationRequestMessage(
                idempotencyKey, "welcome", "account", "TRANSACTIONAL", "NORMAL", Set.of("EMAIL"),
                List.of(new NotificationRequestMessage.RecipientMessage(null, address, "en", null, Map.of())),
                Map.of("productName", "Ludwig"), null);
        String topic = application.getEnvironment().getRequiredProperty("ludwig.notification.ingress.topic");

        KafkaDeliveries.duplicate(
                IngressSeed.stringTemplate(application.getBean(KafkaProperties.class)),
                new ProducerRecord<>(topic, idempotencyKey,
                        application.getBean(ObjectMapper.class).writeValueAsString(message)));

        LocalMailbox mailbox = application.getBean(LocalMailbox.class);
        await().atMost(DELIVERED_WITHIN).untilAsserted(() -> assertThat(sentTo(mailbox, address)).isEqualTo(1));
        // The second record has certainly been consumed by now; give a second email time to appear
        // if one were ever going to.
        await().during(STAYS_SINGLE_FOR).atMost(STAYS_SINGLE_FOR.plusSeconds(5))
                .untilAsserted(() -> assertThat(sentTo(mailbox, address)).isEqualTo(1));
    }

    private static long sentTo(LocalMailbox mailbox, String address) throws MessagingException {
        long count = 0;
        for (MimeMessage message : mailbox.received()) {
            if (address.equals(message.getAllRecipients()[0].toString())) {
                count++;
            }
        }
        return count;
    }
}
