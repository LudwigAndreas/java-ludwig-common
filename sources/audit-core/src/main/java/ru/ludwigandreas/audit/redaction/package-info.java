/**
 * Classification and masking of sensitive values, for everything this platform writes down.
 *
 * <p>Inside {@code audit-core} rather than in a third module, because {@code audit-core} already
 * guarantees zero in-repo dependencies - which is the only property a separate {@code redaction-core}
 * would have added. The consequence is worth stating because it looks odd until you know why: a module
 * importing an audit module purely for its redaction package is the intended arrangement, not a mistake.
 * {@code observability-spring-boot-starter} already does it, for {@link Redaction#MASK} alone, so that an
 * operator reading a log line and an auditor reading the trail see one marker rather than two.
 *
 * <p>Two concerns, kept apart. A {@link ru.ludwigandreas.audit.redaction.SensitivityClassifier} decides
 * what is sensitive and there are four of them, one per rule the platform actually had - by key name,
 * by provenance, by declaration, by configured list - composed as a union. A
 * {@link ru.ludwigandreas.audit.redaction.Redactor} decides what happens to a value once one of them has
 * said so, and there is one, covering scalars, attribute maps, header multimaps, JSON trees and
 * form-encoded bodies.
 */
package ru.ludwigandreas.audit.redaction;
