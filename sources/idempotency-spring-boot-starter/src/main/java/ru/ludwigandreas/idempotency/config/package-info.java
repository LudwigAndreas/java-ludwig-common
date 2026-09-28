/**
 * The module's autoconfiguration, split by what each half needs: the fingerprinter and the metrics need
 * nothing, the store needs a {@code DataSource}, the filter needs a servlet stack, the record filter needs
 * spring-kafka, and the purge needs {@code job-core}'s lock. A service gets exactly the halves it can
 * support, and a missing dependency produces no bean rather than a startup failure about something it never
 * asked for.
 */
package ru.ludwigandreas.idempotency.config;
