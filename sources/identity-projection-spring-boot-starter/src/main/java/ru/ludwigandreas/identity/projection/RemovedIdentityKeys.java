package ru.ludwigandreas.identity.projection;

import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Fails startup while the deployment still sets {@code ludwig.identity.kafka.topic}. Binding ignores unknown keys, so
 * without this a service upgraded with its old topic would start with no subscription and quietly stop projecting.
 */
public class RemovedIdentityKeys implements InitializingBean {

    private static final String TOPIC = "ludwig.identity.kafka.topic";

    private final ConfigurableEnvironment environment;

    /**
     * Creates the check.
     *
     * @param environment the application's environment
     */
    public RemovedIdentityKeys(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        boolean set = environment.getPropertySources().stream()
                .filter(EnumerablePropertySource.class::isInstance)
                .flatMap(source -> List.of(((EnumerablePropertySource<?>) source).getPropertyNames()).stream())
                .anyMatch(TOPIC::equals);
        if (set) {
            throw new SecurityConfigurationException(TOPIC + " is no longer read: declare the source under"
                    + " ludwig.identity.sources.<source> and subscribe it with"
                    + " ludwig.identity.kafka.subscriptions.<source>.topic"
                    + " (identity-source-kafka-spring-boot-starter)");
        }
    }
}
