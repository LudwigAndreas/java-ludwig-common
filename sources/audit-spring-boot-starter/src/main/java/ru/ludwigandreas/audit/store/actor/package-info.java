/**
 * One answer to "who is acting", for both the audit trail and {@code db-core}'s field stamping.
 *
 * <p>There were two paths to that answer - {@code SpringSecurityAuditorProvider} for entity columns and
 * {@code SecurityPrincipals.currentSubject()} for the settings audit trail - which is one too many: when
 * they disagree, a row's {@code last_modified_by} and the event describing that modification name the same
 * person differently.
 */
package ru.ludwigandreas.audit.store.actor;
