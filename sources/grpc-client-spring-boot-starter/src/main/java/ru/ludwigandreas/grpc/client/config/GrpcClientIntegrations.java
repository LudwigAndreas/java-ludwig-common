package ru.ludwigandreas.grpc.client.config;

import java.util.function.BiFunction;
import java.util.function.Supplier;
import ru.ludwigandreas.grpc.client.properties.ClientSettings;
import ru.ludwigandreas.outboundtoken.CachedToken;

/**
 * What an optional module contributes to the client pipeline, in types that name none of them -
 * so the auto-configuration that assembles the pipeline loads no optional class.
 */
public final class GrpcClientIntegrations {

    private GrpcClientIntegrations() {
    }

    /**
     * The caller's correlation id, from observability.
     *
     * @param headerName the correlation header, sent lower-cased as metadata
     * @param current    the current id, or {@code null}
     */
    public record Correlation(String headerName, Supplier<String> current) {
    }

    /**
     * The caller, from Spring Security.
     *
     * @param inboundToken   the current request's bearer token, or {@code null}
     * @param principal      the current principal's name, or {@code null}
     * @param resourceServer whether this service decodes inbound tokens at all
     */
    public record Security(Supplier<String> inboundToken, Supplier<String> principal, boolean resourceServer) {
    }

    /**
     * Machine tokens, from the OAuth2 client.
     *
     * @param minters a token minter for a client's auth block
     */
    public record ClientCredentials(BiFunction<String, ClientSettings.Auth, Supplier<CachedToken>> minters) {
    }
}
