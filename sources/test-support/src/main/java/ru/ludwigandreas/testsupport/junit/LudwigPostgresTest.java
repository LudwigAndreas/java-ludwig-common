package ru.ludwigandreas.testsupport.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

/**
 * Boots an application context against the shared PostgreSQL.
 *
 * <h2>What this turns on</h2>
 *
 * <ul>
 *   <li>{@code @SpringBootTest}, so the context is the real one.</li>
 *   <li>The shared, digest-pinned PostgreSQL singleton, wired by {@code @ServiceConnection} - so no
 *       {@code @DynamicPropertySource} block copying a JDBC URL.</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto=none}, so the schema comes from the module's Liquibase
 *       changelog and Hibernate validates its mappings against it. A test whose schema was generated
 *       from the entities cannot fail on a column the changelog forgot, which is the one failure that
 *       otherwise waits for production.</li>
 * </ul>
 *
 * <h2>What it deliberately does not turn on</h2>
 *
 * <p>It does not touch {@code spring.liquibase.enabled}, because the right answer differs per module:
 * a starter applies its own changelog with its own {@code SpringLiquibase} runner and wants Boot's off,
 * while a service wants Boot's on. Guessing would break one of the two, so each module states it.
 *
 * <p>It does not empty the database between tests. One shared container means a test cannot assume an
 * empty schema by construction; that is the deliberate trade
 * {@link ru.ludwigandreas.testsupport.container.Containers} explains, and
 * {@link ru.ludwigandreas.testsupport.data.DatabaseCleaner} is how a module pays it in one
 * {@code @BeforeEach}.
 *
 * <h2>When not to use it</h2>
 *
 * <p>When the thing under test does not need a database. A unit test with a mocked repository proves
 * the same thing in milliseconds, and this platform's convention is that a class named {@code *IT} or
 * {@code *IntegrationTest} is the only kind failsafe runs at all - so a test annotated with this and
 * named otherwise will silently never run.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none"
})
public @interface LudwigPostgresTest {
}
