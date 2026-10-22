package ru.ludwigandreas.notification.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.springwolf.asyncapi.v3.jackson.AsyncApiSerializerService;
import io.github.springwolf.core.asyncapi.AsyncApiService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.docs.PublishedDocument;

/**
 * Publishes this service's Kafka surface to {@code docs/api/} and fails the build when the committed
 * AsyncAPI document no longer matches it. The check itself is {@link PublishedDocument}'s, shared with
 * {@link ApiDocumentIT}.
 *
 * <p>Not a {@link NotificationTestBase}: that base switches the Kafka consumers off and the user-settings
 * projection on. Here the ingress and the identity consumer are on, because the document describes every
 * topic this service can read, and the projection is off, because its module is not part of the packaged
 * application. No broker is needed: the document comes from the context.
 *
 * <p>{@link DirtiesContext}: a context of its own that nothing else reuses, closed once the test ends so
 * that its connection pool does not stay open against the PostgreSQL container the rest of the suite shares.
 */
@SpringBootTest
@Import({TestSecurityConfiguration.class, PostgresContainerConfiguration.class})
@TestPropertySource(properties = {
        "ludwig.notification.queue.poller-enabled=false",
        "ludwig.notification.retention.enabled=false",
        "ludwig.notification.ingress.kafka-enabled=true",
        "ludwig.identity.kafka.enabled=true",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.bootstrap-servers=localhost:9",
        "ludwig.outbox.polling.enabled=false",
        "ludwig.notification.receipts.signing-secret=test-receipt-secret",
        "ludwig.notification.channels.email.from-address=no-reply@notifications.test"
})
@DirtiesContext
class AsyncApiDocumentIT {

    private static final String ARTIFACT_ID = "notification-service";

    @Autowired
    private AsyncApiService asyncApi;

    @Autowired
    private AsyncApiSerializerService serializer;

    @Test
    @DisplayName("the committed AsyncAPI document matches what this service sends and reads")
    void committedDocumentIsCurrent() throws Exception {
        PublishedDocument.check(ARTIFACT_ID, PublishedDocument.Kind.ASYNCAPI,
                new ObjectMapper().readTree(serializer.toJsonString(asyncApi.getAsyncAPI())), AsyncApiDocumentIT.class);
    }
}
