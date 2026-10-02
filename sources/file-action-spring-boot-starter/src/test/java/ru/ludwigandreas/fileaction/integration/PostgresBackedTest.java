package ru.ludwigandreas.fileaction.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.ludwigandreas.testsupport.container.Containers;

/**
 * The Postgres every integration test in this module shares.
 *
 * <p>One container for the whole run, from {@code test-support}, whose {@code images.properties} pins it by name,
 * version and digest - which is where that pin belongs rather than here.
 *
 * <p>Boot's own Liquibase is off and only the starters' independent runners apply anything, which is the
 * arrangement a real consumer has: its own changelog alongside this module's rather than instead of it. A test that
 * let Boot apply this module's changelog would not be exercising the autoconfiguration a consumer actually gets.
 */
abstract class PostgresBackedTest {

    /** The shared container, started once for the whole run. */
    static final PostgreSQLContainer<?> POSTGRES = Containers.postgres();

    /**
     * Points the context at the container.
     *
     * @param registry the registry
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.liquibase.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }
}
