/**
 * The platform's single audit envelope, sink SPI and failure policy.
 *
 * <p>Nine modules in this repository each invented an audit mechanism: nine SPIs, seven SLF4J
 * implementations, three persistent stores with three different schemas and three different redaction
 * markers. Everything module-specific about those events survives - in the modules' own typed records,
 * which keep their shape at the call site and gain a {@code toAuditEvent()} - and everything generic
 * about them is here.
 *
 * <p>This package and {@link ru.ludwigandreas.audit.redaction} depend on <strong>no other module in
 * this repository</strong>, and must not be given one. {@code security-spring-boot-starter},
 * {@code rest-client-spring-boot-starter} and {@code hot-reload-spring-boot-starter} all sit below
 * {@code db-core} and all need to audit; Maven's reactor DAG ignores scope, so even an
 * {@code optional} edge from here to {@code db-core} would be a cycle the moment anything upstream of
 * {@code db-core} wanted a trail. The JPA sink, the outbox sink, the retention purge and the query API
 * live in {@code audit-spring-boot-starter} for that reason, which is the same core/starter split the
 * repository already uses for {@code db-core} and {@code web-core-spring-boot-starter}.
 */
package ru.ludwigandreas.audit;
