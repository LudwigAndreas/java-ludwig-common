/**
 * The published surface of the idempotency module: the store contract, its two claim modes, the
 * request fingerprint, the replayable response and the two headers a call carries.
 *
 * <p>A consumer writes against this package alone. Nothing here depends on the JPA entity, the
 * Postgres statements, the servlet filter or the autoconfiguration, which is what lets a service use
 * the store from a Kafka listener without pulling a web stack in, and what lets the Redis backend be a
 * real alternative rather than a subset.
 */
package ru.ludwigandreas.idempotency.api;
