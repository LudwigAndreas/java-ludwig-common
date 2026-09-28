/**
 * The trail's read side and its retention purge statements.
 *
 * <p>Deliberately not reachable from {@code AuditSink}: the write side has one method and cannot be
 * asked questions, so that a module emitting events has no way to read or filter what it emitted.
 */
package ru.ludwigandreas.audit.store.repository;
