package ru.ludwigandreas.restclient.spi;

import org.springframework.core.Ordered;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The {@code async} counterpart of {@link LudwigRestClientCustomizer}, with the same ordering and
 * the same precedence.
 *
 * <p>Separate interface rather than one generic type: the two builders share no supertype, and a
 * single {@code customize(Object)} would move the failure from compile time to the first call. A
 * service that runs one client in each mode writes two small customizers, which is also an honest
 * reflection of the fact that an {@code ExchangeFilterFunction} and a
 * {@code ClientHttpRequestInterceptor} are not the same thing.
 */
public interface LudwigWebClientCustomizer extends Ordered {

    /** Whether this customizer applies to {@code clientName}. Defaults to every client. */
    default boolean supports(String clientName) {
        return true;
    }

    /** Adjusts the builder for {@code clientName}, which is already fully configured from YAML. */
    void customize(String clientName, WebClient.Builder builder);

    @Override
    default int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
