package ru.ludwigandreas.example.catalog.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.springwolf.asyncapi.v3.jackson.AsyncApiSerializerService;
import io.github.springwolf.core.asyncapi.AsyncApiService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.docs.PublishedDocument;

/**
 * Publishes this service's Kafka surface to {@code docs/api/} and fails the build when the committed
 * AsyncAPI document no longer matches it. The check itself is {@link PublishedDocument}'s, shared with
 * {@link ApiDocumentIT}.
 *
 * <p>The identity projection's consumer is left on, unlike in {@link ApiDocumentIT}, because it is part of
 * what this service reads in production. No broker is needed: the document comes from the context.
 */
@SpringBootTest
@Import({TestSecurityConfiguration.class, PostgresContainerConfiguration.class})
@TestPropertySource(properties = {
        "ludwig.outbox.polling.enabled=false",
        "spring.kafka.bootstrap-servers=localhost:9",
        "spring.jpa.properties.hibernate.generate_statistics=false"
})
class AsyncApiDocumentIT {

    private static final String ARTIFACT_ID = "crud-service-example";

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
