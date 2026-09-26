/**
 * The per-category retention purge.
 *
 * <p>Optional, behind {@code @ConditionalOnClass} on {@code job-core}: a deployment that wants only the
 * trail should not be made to put a scheduler on its classpath. Without it nothing purges, which a
 * service must notice for itself - the README says so, because an audit table that grows forever is a
 * disclosure risk nobody is watching.
 */
package ru.ludwigandreas.audit.store.retention;
