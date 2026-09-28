/**
 * The persistent and off-platform sinks.
 *
 * <p>Composable, because several at once is the normal configuration: a deployment logs the trail
 * through {@code audit-core}'s SLF4J sink for the operators, writes it here for the auditors, and ships
 * it through the outbox to a SIEM, all from one {@code AuditSink} call.
 *
 * <p><strong>Out of scope, as a decision rather than an omission: hash-chaining or signing for tamper
 * evidence.</strong> It is a real feature and it is not half-built here. It needs key management and a
 * verification tool to mean anything, and half of it - a chain nobody verifies - is worse than none,
 * because it looks like a guarantee. The append-only enforcement in
 * {@code ru.ludwigandreas.audit.store.entity} is what this module does claim: no code path updates a row
 * and none deletes one outside the retention purge.
 */
package ru.ludwigandreas.audit.store.sink;
