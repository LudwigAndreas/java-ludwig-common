package ru.ludwigandreas.cache.unit;

/**
 * A cache key for the tests.
 *
 * @param id the value the key renderer produces, so a test can predict the shared key
 */
record TestKey(String id) {
}
