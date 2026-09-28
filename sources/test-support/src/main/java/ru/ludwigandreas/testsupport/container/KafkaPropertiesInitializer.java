package ru.ludwigandreas.testsupport.container;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Points {@code spring.kafka.bootstrap-servers} at the shared broker.
 *
 * <h2>Why not {@code @ServiceConnection}</h2>
 *
 * <p>This is a trap worth writing down, because the failure names the wrong thing. Spring Boot 3.3's
 * connection-details support knows the older {@code org.testcontainers.containers.KafkaContainer} but
 * not the KRaft-native {@code org.testcontainers.kafka.KafkaContainer} this platform uses. Annotate the
 * KRaft one with {@code @ServiceConnection} and the annotation resolves to nothing: the context then
 * fails with a message naming the bean rather than the cause, and the obvious next move - adding the
 * connection name, which is what a digest-pinned Postgres container genuinely needs - does not help.
 *
 * <p>So the broker is wired by property, as {@code KafkaIngressIntegrationTest},
 * {@code SharedConsumerIT} and {@code ProjectionModeIntegrationTest} each did by hand.
 *
 * <h2>Why an initializer rather than {@code @DynamicPropertySource}</h2>
 *
 * <p>{@code @DynamicPropertySource} is a static method on the test class and cannot be inherited from a
 * meta-annotation, so every test would repeat the block. An {@code ApplicationContextInitializer} can be
 * named by {@code @ContextConfiguration}, which {@link ru.ludwigandreas.testsupport.junit.LudwigKafkaTest}
 * carries - so the test writes nothing.
 *
 * <p>Spring Boot 3.4's {@code DynamicPropertyRegistrar} bean would be tidier; this repository is on
 * 3.3.5, where it does not exist. Replace this when the Boot line moves - and check then whether
 * connection-details support has caught up with the KRaft container, which would remove it entirely.
 */
public class KafkaPropertiesInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestPropertyValues.of(
                        "spring.kafka.bootstrap-servers=" + Containers.kafka().getBootstrapServers())
                .applyTo(context);
    }
}
