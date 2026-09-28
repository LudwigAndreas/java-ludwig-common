package ru.ludwigandreas.restclient.auth.provider;

/**
 * Counts token mints, so that "the IdP is being hammered" is visible as a metric rather than
 * inferred from its logs.
 *
 * <p>A one-method interface rather than a direct {@code MeterRegistry} dependency: the auth
 * providers are the part of this module most likely to be reimplemented by a service, and a
 * provider that has to construct a Micrometer counter in order to be written is a provider people
 * will write without one.
 */
@FunctionalInterface
public interface TokenRefreshCounter {

    /** Records one token mint for {@code clientName}. */
    void tokenRefreshed(String clientName, String authType);
}
