/**
 * The append-only {@code audit_event} table.
 *
 * <p>One entity, because there is one table. Everything that used to be a shaped audit table in this
 * repository - {@code user_setting_audit}, {@code sync_audit_record} - is a row in it, migrated by the
 * {@code audit-005} and {@code audit-006} changesets rather than left behind in a table nobody queries.
 * {@code outbox_status_history} is the deliberate exception and stays where it is; the reason is in this
 * module's README and in the outbox module's.
 */
package ru.ludwigandreas.audit.store.entity;
