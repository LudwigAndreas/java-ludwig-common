package ru.ludwigandreas.cache.unit;

/**
 * A cached value for the tests.
 *
 * <p>A record with two components, so {@link ru.ludwigandreas.cache.shared.ValueShape} has something to
 * fingerprint and a test can change its shape by using a different type.
 *
 * @param text  something to assert on
 * @param count something to assert on
 */
record TestValue(String text, int count) {
}
