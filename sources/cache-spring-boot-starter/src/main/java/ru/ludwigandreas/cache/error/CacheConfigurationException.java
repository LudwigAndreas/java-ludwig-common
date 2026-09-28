package ru.ludwigandreas.cache.error;

/**
 * A caching configuration that cannot be started.
 *
 * <p>Thrown from the startup validator and from the registry, never from a read or a write. Every
 * message names the cache, because {@code ludwig.cache.caches} is a map and "the TTL is too long" is
 * not actionable without knowing which one.
 */
public class CacheConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** @param message what is unsafe about the configuration, and what it causes */
    public CacheConfigurationException(String message) {
        super(message);
    }

    /**
     * @param message what is unsafe about the configuration
     * @param cause   the underlying failure
     */
    public CacheConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
