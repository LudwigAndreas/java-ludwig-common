package ru.ludwigandreas.reconciliation.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.restclient.core.RestClientRegistry;

/**
 * Teaches the startup validator about the service's named HTTP clients, when
 * {@code rest-client-spring-boot-starter} is on the classpath.
 *
 * <p>Isolated in its own configuration class so that the reference to {@code RestClientRegistry}
 * exists only where {@code @ConditionalOnClass} has already established that the type does.
 */
@AutoConfiguration
@ConditionalOnClass(RestClientRegistry.class)
@AutoConfigureBefore(ReconciliationAutoConfiguration.class)
public class ReconciliationRestClientAutoConfiguration {

    /**
     * Answers the validator's "is this client configured?" from the real registry.
     *
     * @param registry the application's REST client registry
     * @return the presence check
     */
    @Bean
    @ConditionalOnBean(RestClientRegistry.class)
    @ConditionalOnMissingBean(RestClientPresence.class)
    public RestClientPresence reconciliationRestClientPresence(RestClientRegistry registry) {
        return registry::contains;
    }
}
