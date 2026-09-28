package ru.ludwigandreas.testsupport.container;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Points the application context at the shared PostgreSQL.
 *
 * <h2>Why {@code @ServiceConnection} and not {@code @DynamicPropertySource}</h2>
 *
 * <p>Every container test in this repository used to carry the same three lines copying
 * {@code getJdbcUrl()}, {@code getUsername()} and {@code getPassword()} into properties. On Spring
 * Boot 3.3 that is what {@code @ServiceConnection} does, and it does more: it also supplies the driver
 * class and it applies to anything else in the context that asks for {@code JdbcConnectionDetails},
 * which a hand-written property block does not.
 *
 * <h2>Why the connection name is given explicitly</h2>
 *
 * <p>Not cosmetic, and the reason is easy to trip over. Spring Boot otherwise deduces which
 * connection-details factory to use by parsing the container's image name, and a name carrying both a
 * tag and a {@code @sha256} digest does not parse as a repository. Because this platform pins every
 * image by digest, the name is always given here. {@code NotificationTestBase} found this first.
 *
 * <h2>When not to use this</h2>
 *
 * <p>A module whose test needs its own database per test class - rather than its own schema - should
 * not import this: it shares one container with everything else in the JVM. That is the right default
 * (see {@link Containers}), and the isolation a test actually needs is almost always
 * {@link ru.ludwigandreas.testsupport.data.DatabaseCleaner} in a {@code @BeforeEach}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfiguration {

    /**
     * The shared container, exposed so Boot can read its connection details.
     *
     * <p>Not a {@code @Bean} that owns a lifecycle: the singleton is started by {@link Containers} and
     * outlives every context. Spring will not stop it, because {@code PostgreSQLContainer} is only
     * {@code Startable}, not a Spring {@code Lifecycle}.
     *
     * @return the shared PostgreSQL container
     */
    @Bean
    @ServiceConnection("postgres")
    public PostgreSQLContainer<?> ludwigPostgresContainer() {
        return Containers.postgres();
    }
}
