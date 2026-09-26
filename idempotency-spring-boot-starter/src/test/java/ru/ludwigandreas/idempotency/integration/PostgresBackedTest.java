package ru.ludwigandreas.idempotency.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real PostgreSQL for the suites below.
 *
 * <h2>Why none of this can be a unit test</h2>
 *
 * <p>Every correctness property of this module is a statement about how PostgreSQL behaves when two
 * transactions meet on one row: {@code ON CONFLICT ... DO UPDATE} making the loser block on the winner's row
 * lock rather than take a constraint violation, {@code RETURNING} handing the loser the committed winner, and
 * the unique index being the enforcement rather than the checking code. A mocked {@code DataSource} would
 * assert that the code sends the strings it sends, which is not the thing that has to be true. An in-memory
 * database would answer differently and pass anyway.
 *
 * <p>A single-threaded test proves nothing here either. Read-then-insert passes one, which is exactly why the
 * contention cases run N threads on one key.
 *
 * <h2>Why the container is a singleton rather than a {@code @Container} field</h2>
 *
 * <p>A {@code static @Container} in a shared base class is started and <em>stopped</em> around each subclass
 * by the Testcontainers extension, while the field itself is initialised once per JVM - so the first suite to
 * finish stops the container the next one is about to use. The documented singleton pattern, started once in a
 * static initialiser and left to the JVM's shutdown and Ryuk to clean up, is the arrangement that actually
 * shares one container across several suites.
 */
abstract class PostgresBackedTest {

    /**
     * Pinned by name, version <em>and</em> {@code sha256} digest.
     *
     * <p>A tag alone can be re-pointed at different content, so a test would silently change what it
     * executes. Company policy is name plus version plus digest everywhere, and a test container is not an
     * exception to it - the same image and digest {@code job-core}'s suite uses.
     */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("postgres:16-alpine@sha256:"
                    + "cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685")
            .asCompatibleSubstituteFor("postgres");

    /** Started once for the whole run. */
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);

    static {
        POSTGRES.start();
    }

    /**
     * Points the context at the container.
     *
     * <p>The schema comes from this module's own shipped changelog, applied by this module's own
     * {@code SpringLiquibase} bean, which makes the migration part of what is under test: a column renamed in
     * the changelog and not in the statement fails here, which is the only place it would fail before
     * production.
     *
     * @param registry the registry Spring hands to a dynamic property source
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The application has no changelog of its own here, so Boot's own Liquibase is off and only the
        // starters' independent runners apply anything - which is the arrangement a real consumer has too,
        // with its own changelog alongside rather than instead.
        registry.add("spring.liquibase.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }
}
