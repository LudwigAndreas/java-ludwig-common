package ru.ludwigandreas.reconciliation.config;

/**
 * Whether a named REST client is configured.
 *
 * <p>An indirection rather than a direct dependency on {@code rest-client-spring-boot-starter},
 * because that starter is optional here and a hard reference in a bean method's signature is enough
 * to fail the whole configuration class with a {@code NoClassDefFoundError} when it is absent - at
 * context refresh, with a stack trace that points at this module rather than at the missing jar.
 *
 * <p>The default implementation answers {@code false} for everything, which is the correct answer
 * when the starter is not on the classpath: a task that names a REST client in a service that has no
 * REST clients is a configuration error, and the validator should say so rather than wave it through.
 */
@FunctionalInterface
public interface RestClientPresence {

    /**
     * Whether {@code name} is a configured client.
     *
     * @param name the client name a task referenced
     * @return whether it exists
     */
    boolean isConfigured(String name);
}
