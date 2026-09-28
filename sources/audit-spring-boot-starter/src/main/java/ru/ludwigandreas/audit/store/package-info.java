/**
 * The audit trail's storage, read side, retention and off-platform shipping.
 *
 * <p>A subtree of {@code ru.ludwigandreas.audit} rather than a package of its own name, because the two
 * artifacts are two halves of one subsystem and a reader looking for the JPA sink looks under the audit
 * package. The envelope, the sink SPI and the redaction package are in {@code audit-core}, which has no
 * in-repo dependencies; everything here needs {@code db-core} and is therefore unavailable to the modules
 * that sit below it.
 */
package ru.ludwigandreas.audit.store;
