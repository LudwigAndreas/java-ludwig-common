/**
 * The autoconfigurations and typed configuration for the shared consumer wiring.
 *
 * <p>Split by what each half needs on the classpath rather than by subject: the core wiring needs only
 * spring-kafka, the dedup policy needs {@code idempotency-spring-boot-starter} to read a claim mode, the
 * metrics need Micrometer, the problem mapping needs web-core, and the retry topics need a deployment to
 * have asked for them twice. A single configuration class would have to be conditional on the union of
 * those, which is how a module ends up wiring nothing in the deployment that needed most of it.
 */
package ru.ludwigandreas.messaging.config;
