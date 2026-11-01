package ru.ludwigandreas.export.security;

import com.querydsl.core.types.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.engine.ReportScopeResolver;
import ru.ludwigandreas.security.data.DataScope;
import ru.ludwigandreas.security.data.DataScopeMapping;
import ru.ludwigandreas.security.data.DataScopePredicateFactory;
import ru.ludwigandreas.security.data.DataScopeRegistry;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationAuthority;
import ru.ludwigandreas.security.operation.Sensitivity;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * Narrows a report's rows to what the requester is entitled to see: the requester's scope for the read
 * operation the definition is {@linkplain ReportDefinition#getScopedBy() scoped by}.
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
 * <p>A definition scoped by an operation that is not a catalogued read over a resource fails startup
 * ({@link #validate}). One whose resource has no registered mapping produces a predicate that matches
 * nothing, not one that matches everything: a report is the worst place to be fail-open, because the
 * failure is not one row too many, it is the whole table in a file somebody emails on.
 */
@Slf4j
public class SecurityReportScopeResolver implements ReportScopeResolver {

    private final OperationAuthority authority;
    private final DataScopeRegistry mappings;
    private final DataScopePredicateFactory predicates;

    public SecurityReportScopeResolver(OperationAuthority authority, DataScopeRegistry mappings,
                                       DataScopePredicateFactory predicates) {
        this.authority = authority;
        this.mappings = mappings;
        this.predicates = predicates;
    }

    @Override
    public Optional<Predicate> scopeFor(ReportDefinition<?, ?> definition, String principalId,
                                        Set<String> authorities) {
        String operationId = definition.getScopedBy();
        if (operationId == null || operationId.isBlank()) {
            // The definition says its rows are not row-scoped. That is a claim its author made, not a
            // default this class chose, so it is honoured rather than second-guessed.
            return Optional.empty();
        }
        Operation operation = authority.require(operationId);
        String resourceType = operation.resource();
        Optional<DataScopeMapping<?>> mapping = mappings.find(resourceType);
        if (mapping.isEmpty()) {
            log.error("Report {} is scoped as resource '{}', for which no DataScopeMapping is"
                            + " registered; the report will return no rows rather than all of them",
                    definition.getKey(), resourceType);
            return Optional.of(predicates.unrestricted().isFalse());
        }
        DataScope scope = authority.scope(principalOf(principalId, authorities), operation);
        return Optional.of(predicates.toPredicate(scope, mapping.get()));
    }

    /**
     * Fails startup on a definition scoped by anything but a catalogued read operation over a resource.
     *
     * @param definitions every registered definition
     * @throws IllegalStateException naming each offending definition
     */
    public void validate(Collection<? extends ReportDefinition<?, ?>> definitions) {
        List<String> problems = new ArrayList<>();
        for (ReportDefinition<?, ?> definition : definitions) {
            String operationId = definition.getScopedBy();
            if (operationId == null || operationId.isBlank()) {
                continue;
            }
            Optional<Operation> operation = authority.grants().catalog().find(operationId);
            if (operation.isEmpty()) {
                problems.add(definition.getKey() + " is scoped by '" + operationId + "', which is not catalogued");
            } else if (operation.get().sensitivity() != Sensitivity.READ || !operation.get().touchesRows()) {
                problems.add(definition.getKey() + " is scoped by '" + operationId
                        + "', which is not a read operation over a resource");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Report scope misconfigured: " + String.join("; ", problems));
        }
    }

    /**
     * The requester, as the scope provider needs to see them.
     *
     * <p>Roles and permissions both carry the whole authority set: roles are what the role table is
     * matched against, and a contributed provider may match on either.
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
