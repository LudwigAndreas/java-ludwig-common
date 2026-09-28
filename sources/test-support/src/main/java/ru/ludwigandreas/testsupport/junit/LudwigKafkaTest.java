package ru.ludwigandreas.testsupport.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.testsupport.container.KafkaPropertiesInitializer;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

/**
 * Boots an application context against the shared PostgreSQL <em>and</em> the shared Kafka broker.
 *
 * <h2>What this turns on</h2>
 *
 * <p>Everything {@link LudwigPostgresTest} turns on, plus the digest-pinned Kafka singleton, wired by
 * {@link KafkaPropertiesInitializer} rather than by {@code @ServiceConnection} - that class explains
 * why the KRaft-native container cannot use the annotation.
 *
 * <p>Both containers, not Kafka alone: every Kafka test in this repository is
 * an ingress test that asserts a consumed record reached the database, so a broker without a database
 * would not be usable on its own. A module that genuinely needs only a broker names
 * {@link KafkaPropertiesInitializer} in its own {@code @ContextConfiguration} instead.
 *
 * <h2>When not to use it</h2>
 *
 * <p>When the assertion is about the listener method rather than about the broker. Calling the listener
 * directly, or using Spring Kafka's embedded broker, proves the same thing without a container start -
 * and this platform's suites deliberately disable {@code auto-startup} in most contexts for that reason.
 * Reach for a real broker when the property under test is one an embedded broker models differently:
 * consumer-group assignment, offset commit behaviour, rebalancing, or a poll loop's timing.
 *
 * <p>Note that a test using this must still set {@code spring.kafka.listener.auto-startup=true} if it
 * wants listeners running; the default here does not change it, because most contexts in this repository
 * want them off and drive the consumer directly.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ContextConfiguration(initializers = KafkaPropertiesInitializer.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none"
})
public @interface LudwigKafkaTest {
}
