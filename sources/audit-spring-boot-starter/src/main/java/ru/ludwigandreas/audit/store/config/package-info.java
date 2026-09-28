/**
 * Autoconfiguration, in four pieces so that each one can be absent.
 *
 * <p>{@code AuditAutoConfiguration} needs nothing but a classpath and produces a working sink on its own.
 * The table, the retention purge and the outbox shipper each add a capability behind their own
 * {@code @ConditionalOnClass}, so a module low in the stack - security, rest-client, hot-reload - gets an
 * audit trail without a database, a scheduler or a broker.
 */
package ru.ludwigandreas.audit.store.config;
