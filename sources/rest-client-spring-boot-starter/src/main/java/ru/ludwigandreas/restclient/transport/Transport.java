package ru.ludwigandreas.restclient.transport;

import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * A built transport: the factory a {@code sync} client executes through, plus whatever handle the
 * engine offers for observing and closing its pool.
 *
 * @param factory  what the client executes through
 * @param poolHandle the engine's native pool object, or {@code null} for an engine that has none.
 *                   Deliberately untyped: {@code PoolingHttpClientConnectionManager} is an Apache
 *                   type that must not be named in a signature this module loads unconditionally,
 *                   and {@code ConnectionPoolGauges} is the one place that narrows it back
 * @param closer   releases the engine's resources on context shutdown, or {@code null}
 */
public record Transport(ClientHttpRequestFactory factory, Object poolHandle, Runnable closer) {

    /** A transport with no pool to observe and nothing to close. */
    public static Transport of(ClientHttpRequestFactory factory) {
        return new Transport(factory, null, null);
    }
}
