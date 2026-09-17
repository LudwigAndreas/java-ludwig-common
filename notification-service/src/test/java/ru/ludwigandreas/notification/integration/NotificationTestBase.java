package ru.ludwigandreas.notification.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Everything the integration tests share: a real PostgreSQL, the real Liquibase migrations, and
 * Hibernate validating its mappings against them at startup.
 *
 * <h2>Why the pollers are switched off</h2>
 *
 * <p>Every scheduled job in this service is driven from an injected {@code TaskScheduler}, so a test
 * can invoke the work directly instead of waiting for a tick. That is not a shortcut around the
 * scheduler - the path under test is identical, claim query included - it is what makes the
 * assertions deterministic. A test that slept until a poller happened to run would be slow when it
 * passed and flaky when it failed, which is the worst combination for the exact properties these
 * tests exist to protect: concurrency, leasing and exactly-once behaviour.
 */
@Testcontainers
@SpringBootTest
@Import(TestSecurityConfiguration.class)
@TestPropertySource(properties = {
        // Driven directly by the tests; see the class comment.
        "ludwig.notification.queue.poller-enabled=false",
        "ludwig.notification.retention.enabled=false",
        // No broker in these tests: the REST ingress is exercised here and the Kafka one has its own
        // test class, so starting a broker for every case would multiply the suite's runtime.
        "ludwig.notification.ingress.kafka-enabled=false",
        "ludwig.identity.kafka.enabled=false",
        "ludwig.outbox.polling.enabled=false",
        // GreenMail, started by the test that needs it, on its standard test-offset port.
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=3025",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "ludwig.notification.receipts.signing-secret=test-receipt-secret",
        "ludwig.notification.channels.email.from-address=no-reply@notifications.test"
})
abstract class NotificationTestBase {

    /**
     * Pinned by name, version <em>and</em> digest.
     *
     * <p>A tag alone can be re-pointed at different content, so a test - and everything else that
     * runs a container - would silently change what it executes. Company policy is name plus version
     * plus digest, everywhere, and a test container is not an exception to it.
     */
    private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE =
            org.testcontainers.utility.DockerImageName
                    .parse("postgres:16-alpine@sha256:"
                            + "cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
                    .asCompatibleSubstituteFor("postgres");

    /**
     * One container for the whole class.
     *
     * <p>The connection name is given explicitly because Spring Boot otherwise deduces it by parsing
     * the image name, and a name carrying both a tag and a digest is not parseable as a repository.
     */
    @Container
    @ServiceConnection("postgres")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE);
}
