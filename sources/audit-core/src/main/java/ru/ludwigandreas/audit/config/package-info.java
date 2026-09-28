/**
 * Autoconfiguration for the parts of auditing that need nothing but a classpath.
 *
 * <p>Here rather than in the starter because the modules that most need a trail are the ones that cannot
 * have the starter: security, rest-client and hot-reload all sit below {@code db-core}. Depending on
 * {@code audit-core} has to be enough to get a working sink, and the SLF4J sink alone is a complete
 * configuration.
 */
package ru.ludwigandreas.audit.config;
