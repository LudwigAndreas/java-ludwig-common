package ru.ludwigandreas.export.engine;

import com.querydsl.core.types.Predicate;
import java.util.Optional;
import java.util.Set;
import ru.ludwigandreas.export.api.ReportDefinition;

/**
 * The data-scope predicate a requester's rows are narrowed by.
 *
 * <p>A seam rather than a direct call into {@code security-spring-boot-starter} because a report is
 * run by a poller thread hours after the request, where there is no security context to read - so the
 * authorities have to be passed in rather than discovered, and an interface is what makes that
 * explicit at the boundary.
 *
 * <p>The default implementation returns no predicate, which is correct for a service whose reports
 * are not row-scoped and is <em>only</em> correct for such a service. A service with data scopes
 * contributes an implementation backed by {@code DataScopeProvider}; the startup validator has no way
 * to tell the two apart, so the decision is deliberately a bean somebody has to write rather than a
 * property somebody could leave at its default.
 */
@FunctionalInterface
public interface ReportScopeResolver {

    /** A resolver that narrows nothing; see the class comment before using it. */
    ReportScopeResolver UNRESTRICTED = (definition, principalId, authorities) -> Optional.empty();

    /**
     * The predicate this requester's rows are narrowed by.
     *
     * @param definition  which report
     * @param principalId who is asking
     * @param authorities their authorities as of now
     * @return the predicate, or empty when this requester's scope is unrestricted
     */
    Optional<Predicate> scopeFor(ReportDefinition<?, ?> definition, String principalId,
                                 Set<String> authorities);
}
