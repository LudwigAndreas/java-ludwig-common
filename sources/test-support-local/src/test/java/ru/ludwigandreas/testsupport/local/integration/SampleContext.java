package ru.ludwigandreas.testsupport.local.integration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;
import ru.ludwigandreas.testsupport.local.integration.sample.SampleApplication;

/**
 * The sample application as an ordinary integration-test context: the application and its database.
 *
 * <p>The sample has a migration, so every context of it needs PostgreSQL; the shared singleton makes
 * that one container for the whole suite.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(classes = SampleApplication.class)
@Import(PostgresContainerConfiguration.class)
public @interface SampleContext {
}
