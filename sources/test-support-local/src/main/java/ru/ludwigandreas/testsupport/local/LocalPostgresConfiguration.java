package ru.ludwigandreas.testsupport.local;

import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.ludwigandreas.testsupport.container.Containers;

/**
 * Connects a local run to {@code test-support}'s shared PostgreSQL.
 *
 * <h2>Why not {@code PostgresContainerConfiguration}</h2>
 *
 * <p>That configuration exposes the container itself as a {@code @ServiceConnection} bean, which
 * hands its lifecycle to the application context: when the context closes, Spring Boot stops the
 * container, unless Testcontainers reuse is enabled on the machine. The container is a per-JVM
 * singleton that every other context in the JVM is also connected to, so one launched application
 * shutting down took the database away from all of them - found by this module's own suite, which
 * launches and closes several applications beside ordinary test contexts.
 *
 * <p>These beans are the same connection details {@code @ServiceConnection} would have derived, read
 * from the same singleton, without the container ever becoming a bean. Liquibase gets details of its
 * own so that a service which configures a separate migration user still migrates the local database
 * and not the one its {@code spring.liquibase.url} names.
 */
@Configuration(proxyBeanMethods = false)
class LocalPostgresConfiguration {

    @Bean
    JdbcConnectionDetails ludwigLocalJdbcConnectionDetails() {
        PostgreSQLContainer<?> postgres = Containers.postgres();
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return postgres.getUsername();
            }

            @Override
            public String getPassword() {
                return postgres.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }
        };
    }

    @Bean
    LiquibaseConnectionDetails ludwigLocalLiquibaseConnectionDetails() {
        PostgreSQLContainer<?> postgres = Containers.postgres();
        return new LiquibaseConnectionDetails() {
            @Override
            public String getUsername() {
                return postgres.getUsername();
            }

            @Override
            public String getPassword() {
                return postgres.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }
        };
    }
}
