package ru.ludwigandreas.cache.error;

import java.util.Collection;

/**
 * A {@code ludwig.cache.caches} block names a cache no module declared.
 *
 * <p>The typo case, and the one that is silent without a check: the block binds, nothing reads it, and
 * the operator concludes the setting does nothing. Listing the declared names is most of the value of
 * the message - the name they meant is almost always one edit away from the one they typed.
 */
public class UnknownCacheException extends CacheConfigurationException {

    private static final long serialVersionUID = 1L;

    /**
     * @param name    the name that was configured or requested
     * @param declared the names modules actually declared
     */
    public UnknownCacheException(String name, Collection<String> declared) {
        super("No cache named '" + name + "' is declared by any module on the classpath. Declared: "
                + (declared.isEmpty() ? "(none)" : String.join(", ", declared))
                + ". A ludwig.cache.caches block for a cache nobody declares is configuration that binds"
                + " and is then ignored, which is why this fails the context instead of being logged.");
    }
}
