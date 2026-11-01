package ru.ludwigandreas.security.operation;

/**
 * Whether an operation's description key resolves in the default bundle. Registered where web-core's message bundles
 * are, so that the operation model itself loads without them.
 */
@FunctionalInterface
public interface OperationDescriptions {

    /**
     * Whether a description key resolves.
     *
     * @param key an operation's description key
     * @return true when the default bundle defines it
     */
    boolean describes(String key);
}
