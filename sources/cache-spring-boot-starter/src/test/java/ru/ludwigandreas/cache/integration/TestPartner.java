package ru.ludwigandreas.cache.integration;

/**
 * A cache key for the shared-tier tests.
 *
 * @param id the value the key renderer produces, so a test can predict the Redis key
 */
record TestPartner(String id) {
}
