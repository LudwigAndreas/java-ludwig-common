package ru.ludwigandreas.testsupport.integration;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.ludwigandreas.testsupport.container.Containers;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

/**
 * A shared container must outlive every application context that is handed it.
 *
 * <p>{@link PostgresContainerConfiguration} exposes the per-JVM singleton as a bean, and Spring Boot
 * closes a container bean when its context closes unless Testcontainers reuse is enabled on the
 * machine - which it is not by default, and not in CI. So one context closing, or failing to load,
 * stopped the database under every other context in the JVM: in a real suite, one connection-limit
 * failure in one test class became ninety-eight "connection refused" errors in the classes after it.
 *
 * <p>The contexts here are built by hand and closed on purpose, because the context a test framework
 * caches is exactly the one that never closes.
 */
class SharedContainerSurvivesContextIntegrationTest {

    @Test
    @DisplayName("closing a context that holds the container as a bean leaves it running, on the same port")
    void survivesAContextClosing() {
        PostgreSQLContainer<?> postgres = Containers.postgres();
        int port = postgres.getFirstMappedPort();

        try (ConfigurableApplicationContext survivor = start()) {
            start().close();

            assertThat(postgres.isRunning()).isTrue();
            assertThat(postgres.getFirstMappedPort()).isEqualTo(port);
            // The context that is still open can still reach it, which is what a suite needs.
            assertThat(new JdbcTemplate(survivor.getBean(DataSource.class))
                    .queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("stop() and close() on a shared container do nothing")
    void ignoresBeingAskedToStop() {
        PostgreSQLContainer<?> postgres = Containers.postgres();
        int port = postgres.getFirstMappedPort();

        postgres.stop();
        postgres.close();

        assertThat(postgres.isRunning()).isTrue();
        assertThat(postgres.getFirstMappedPort()).isEqualTo(port);
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(TestSupportApplication.class, PostgresContainerConfiguration.class)
                .web(WebApplicationType.NONE)
                .run();
    }
}
