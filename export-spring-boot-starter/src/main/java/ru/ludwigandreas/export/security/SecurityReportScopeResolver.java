package ru.ludwigandreas.export.security;

import com.querydsl.core.types.Predicate;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.engine.ReportScopeResolver;
import ru.ludwigandreas.security.data.DataAction;
import ru.ludwigandreas.security.data.DataScope;
import ru.ludwigandreas.security.data.DataScopeMapping;
import ru.ludwigandreas.security.data.DataScopePredicateFactory;
import ru.ludwigandreas.security.data.DataScopeProvider;
import ru.ludwigandreas.security.data.DataScopeRegistry;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * Narrows a report's rows to what the requester is entitled to see.
 *
 * <h2>The principal is rebuilt, not read</h2>
 *
 * <p>A deferred run executes on a poller thread where {@code SecurityContextHolder} is empty, so this
 * resolver assembles a {@link LudwigPrincipal} from the subject on the run and the authorities
 * <em>re-resolved for this attempt</em>. That is not a workaround for the missing context - it is the
 * point. Reading the context would mean a synchronous run scoped itself from the live principal and a
 * deferred one could not scope itself at all, which is the asymmetry that turns a deferred report
 * into the one bulk read in the estate with no row-level access control.
 *
 * <h2>Fail closed</h2>
 *
 * <p>A definition that declares a scope resource with no registered mapping produces a predicate that
 * matches nothing, not one that matches everything. {@code DataScopeProvider} says implementations
 * must be fail-closed for exactly this reason, and a report is the worst place to be fail-open: the
 * failure is not one row too many, it is the whole table in a file somebody emails on.
 */
@Slf4j
public class SecurityReportScopeResolver implements ReportScopeResolver {

    private final DataScopeProvider scopes;
    private final DataScopeRegistry mappings;
    private final DataScopePredicateFactory predicates;

    public SecurityReportScopeResolver(DataScopeProvider scopes, DataScopeRegistry mappings,
                                       DataScopePredicateFactory predicates) {
        this.scopes = scopes;
        this.mappings = mappings;
        this.predicates = predicates;
    }

    @Override
    public Optional<Predicate> scopeFor(ReportDefinition<?, ?> definition, String principalId,
                                        Set<String> authorities) {
        String resourceType = definition.getScopeResourceType();
        if (resourceType == null || resourceType.isBlank()) {
            // The definition says its rows are not row-scoped. That is a claim its author made, not a
            // default this class chose, so it is honoured rather than second-guessed.
            return Optional.empty();
        }
        Optional<DataScopeMapping<?>> mapping = mappings.find(resourceType);
        if (mapping.isEmpty()) {
            log.error("Report {} is scoped as resource '{}', for which no DataScopeMapping is"
                            + " registered; the report will return no rows rather than all of them",
                    definition.getKey(), resourceType);
            return Optional.of(predicates.unrestricted().isFalse());
        }
        DataScope scope = scopes.scopeFor(principalOf(principalId, authorities), resourceType,
                DataAction.READ);
        return Optional.of(predicates.toPredicate(scope, mapping.get()));
    }

    /**
     * The requester, as the scope provider needs to see them.
     *
     * <p>Roles and permissions both carry the whole authority set. The provider matches on whichever
     * of the two its policies are written against, and a set that appeared in one and not the other
     * would make a report's scope depend on how the estate happened to spell an entitlement.
     */
    private LudwigPrincipal principalOf(String principalId, Set<String> authorities) {
        return LudwigPrincipal.builder()
                .subject(principalId)
                .type(PrincipalType.USER)
                .roles(authorities)
                .permissions(authorities)
                .build();
    }
}
