package ru.ludwigandreas.cache.error;

/**
 * A loader could not produce a value.
 *
 * <p>Wraps a checked cause; a {@link RuntimeException} from a loader propagates as itself so that a
 * handler which already recognises it still does. Either way the failure is <b>not cached</b> - see
 * {@link ru.ludwigandreas.cache.api.CacheLoad} for what caching one does to a brief outage - so the next
 * call for the same key retries.
 */
public class CacheLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param cacheName the cache whose loader failed
     * @param cause     what the loader reported
     */
    public CacheLoadException(String cacheName, Throwable cause) {
        super("Loading a value for cache '" + cacheName + "' failed; the failure is not cached, so the"
                + " next call for the same key will retry", cause);
    }
}
