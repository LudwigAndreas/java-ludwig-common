package ru.ludwigandreas.export.security;

import java.util.Set;

/**
 * What a requester may currently do, resolved at the moment it is asked.
 *
 * <p>A seam rather than a direct call into {@code security-spring-boot-starter}, because a deferred
 * run resolves authorities on a poller thread hours after the request, where there is no security
 * context to read from. Passing a subject and getting back a set is the only shape that works in
 * both places - a request thread and a background one - and having one shape is what stops the two
 * paths from disagreeing about what a requester may see.
 *
 * <p>The implementation this module ships delegates to {@code AuthorityResolver}, which
 * {@code identity-projection} backs with a cache that is evicted on change. That eviction is the
 * whole reason re-resolving at execution time is worth doing: a revocation reaches a running report
 * in milliseconds rather than at the next token refresh.
 */
@FunctionalInterface
public interface ReportAuthorities {

    /** A resolver that grants nothing, for a service that has not wired one. */
    ReportAuthorities NONE = principalId -> Set.of();

    /**
     * The authorities this subject currently holds.
     *
     * @param principalId the requester's subject
     * @return their roles and permissions as one set; never null, and empty for a subject this
     *         service has no grant on record for - which is a normal answer, not an error
     */
    Set<String> forPrincipal(String principalId);
}
