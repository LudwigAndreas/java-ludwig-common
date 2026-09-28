package ru.ludwigandreas.cache.integration;

/**
 * A cached value for the shared-tier tests, serialized into Redis as JSON.
 *
 * @param name  something to assert on
 * @param tier  something to assert on
 */
record PartnerRecord(String name, int tier) {
}
