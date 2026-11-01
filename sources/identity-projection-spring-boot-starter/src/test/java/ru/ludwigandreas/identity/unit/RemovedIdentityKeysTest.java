package ru.ludwigandreas.identity.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import ru.ludwigandreas.identity.projection.RemovedIdentityKeys;

class RemovedIdentityKeysTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Keys.class);

    @Configuration(proxyBeanMethods = false)
    static class Keys {

        @Bean
        RemovedIdentityKeys removedIdentityKeys(ConfigurableEnvironment environment) {
            return new RemovedIdentityKeys(environment);
        }
    }

    @Test
    @DisplayName("the former topic key fails startup, naming its replacement")
    void removedTopicKey() {
        runner.withPropertyValues("ludwig.identity.kafka.topic=oidc.users").run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("ludwig.identity.kafka.subscriptions.<source>.topic"));
    }

    @Test
    @DisplayName("an unrelated identity Kafka key is not refused")
    void otherKafkaKeysPass() {
        runner.withPropertyValues("ludwig.identity.kafka.group-id=svc").run(context ->
                assertThat(context).hasNotFailed());
    }
}
