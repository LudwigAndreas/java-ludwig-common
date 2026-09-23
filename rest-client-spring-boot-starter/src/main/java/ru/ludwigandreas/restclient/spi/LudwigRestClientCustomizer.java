package ru.ludwigandreas.restclient.spi;

import org.springframework.core.Ordered;
import org.springframework.web.client.RestClient;

/**
 * Last word on how a {@code sync} client's builder is configured.
 *
 * <p>Customizers are Spring beans and run <em>after</em> every YAML-derived setting has been applied,
 * in {@link Ordered} order, which is what makes them an override rather than a default. They exist
 * for the things a property tree cannot express - a message converter for a partner's vendor media
 * type, an interceptor that signs a body, a {@code UriBuilderFactory} with a non-standard encoding
 * mode - and are deliberately the fourth of the five precedence levels: property defaults, the
 * {@code defaults} block, the client block, customizer beans, per-request overrides.
 *
 * <p>Naming a client in {@link #supports} is strongly preferred over customizing all of them. A
 * customizer that returns {@code true} for everything will also run on clients added next year by
 * somebody who has never read it.
 */
public interface LudwigRestClientCustomizer extends Ordered {

    /** Whether this customizer applies to {@code clientName}. Defaults to every client. */
    default boolean supports(String clientName) {
        return true;
    }

    /** Adjusts the builder for {@code clientName}, which is already fully configured from YAML. */
    void customize(String clientName, RestClient.Builder builder);

    @Override
    default int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
