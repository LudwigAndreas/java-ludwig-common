package ru.ludwigandreas.identity.kafka.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.ludwigandreas.identity.feed.IdentityFeed;
import ru.ludwigandreas.identity.feed.IdentityProjection;
import ru.ludwigandreas.identity.kafka.IdentityKafkaFeed;
import ru.ludwigandreas.identity.kafka.IdentityUserEventListener;
import ru.ludwigandreas.identity.kafka.config.IdentityKafkaAutoConfiguration;
import ru.ludwigandreas.messaging.config.KafkaClustersAutoConfiguration;
import ru.ludwigandreas.messaging.config.MessagingAutoConfiguration;
import ru.ludwigandreas.messaging.config.MessagingMetricsAutoConfiguration;

class IdentityKafkaAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, KafkaAutoConfiguration.class,
                    KafkaClustersAutoConfiguration.class, MessagingMetricsAutoConfiguration.class,
                    MessagingAutoConfiguration.class, IdentityKafkaAutoConfiguration.class))
            .withBean(IdentityProjection.class, () -> mock(IdentityProjection.class))
            .withPropertyValues("spring.kafka.bootstrap-servers=localhost:1",
                    "spring.kafka.listener.auto-startup=false",
                    "spring.application.name=svc");

    @Test
    @DisplayName("a subscription registers the listener on its own container factory, and one feed per source")
    void subscriptionWiresTheListener() {
        runner.withPropertyValues("ludwig.identity.kafka.group-id=svc-identity",
                        "ludwig.identity.kafka.subscriptions.staff.topic=corp.staff.users",
                        "ludwig.identity.kafka.subscriptions.customers.topic=shop.customer.users")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean(IdentityKafkaFeed.CONTAINER_FACTORY);
                    assertThat(context).hasSingleBean(IdentityUserEventListener.class);
                    assertThat(context.getBeansOfType(IdentityFeed.class).values())
                            .extracting(IdentityFeed::source).containsExactlyInAnyOrder("staff", "customers");
                    assertThat(context.getBean("identityKafkaFeed.staff", IdentityFeed.class).status())
                            .containsEntry("topic", "corp.staff.users").containsEntry("groupId", "svc-identity");
                });
    }

    @Test
    @DisplayName("ludwig.identity.kafka.enabled=false registers no identity listener")
    void disabled() {
        runner.withPropertyValues("ludwig.identity.kafka.enabled=false", "ludwig.identity.kafka.group-id=g",
                        "ludwig.identity.kafka.subscriptions.staff.topic=corp.staff.users")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IdentityUserEventListener.class);
                    assertThat(context).doesNotHaveBean(IdentityKafkaFeed.CONTAINER_FACTORY);
                });
    }

    @Test
    @DisplayName("without a subscription the starter registers nothing")
    void noSubscription() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(IdentityUserEventListener.class);
            assertThat(context.getBeansOfType(IdentityFeed.class)).isEmpty();
        });
    }

    @Test
    @DisplayName("a subscription without a consumer group fails startup, naming the key")
    void missingGroupFails() {
        runner.withPropertyValues("ludwig.identity.kafka.subscriptions.staff.topic=corp.staff.users")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("ludwig.identity.kafka.group-id"));
    }
}
