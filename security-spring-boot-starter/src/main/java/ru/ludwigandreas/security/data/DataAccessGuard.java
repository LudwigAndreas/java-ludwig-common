package ru.ludwigandreas.security.data;

import com.querydsl.core.types.dsl.BooleanExpression;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import ru.ludwigandreas.security.audit.AccessAuditLogger;
import ru.ludwigandreas.security.audit.AccessDecision;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.SecurityPrincipals;

/**
 * The one entry point application code uses for data-level authorization.
 *
 * <p>Two shapes, and using both is the point:
 *
 * <pre>{@code
 * // Query time - the scope becomes part of the WHERE clause, so paging and counts stay correct.
 * queryFactory.selectFrom(order)
 *     .where(criteria.and(guard.predicate("order", DataAction.READ, ORDER_MAPPING)))
 *
 * // Load time - the safety net for every path that did not go through a scoped query.
 * OrderEntity entity = repository.getByIdOrThrow(id);
 * guard.check("order", DataAction.READ, entity);
 * }</pre>
 *
 * <p>The pre-filter alone is not sufficient and the post-check alone is not efficient. Pre-filtering
 * misses direct loads, relation traversals and objects rebuilt from events; post-checking a list would
 * break paging and still read every row. Together they cover the surface, which is why the mapping
 * declares a column and an accessor for each dimension rather than one or the other.
 *
 * <p>Denials are audited and counted here rather than at the call sites, so a service cannot forget
 * to and so the signal is uniform across resources.
 */
@RequiredArgsConstructor
public class DataAccessGuard {

    private final DataScopeProvider scopeProvider;
    private final DataScopeRegistry registry;
    private final DataScopePredicateFactory predicateFactory;
    private final AccessAuditLogger auditLogger;
    private final SecurityMetrics metrics;

    /** The caller's scope for this resource and action, before it is compiled into anything. */
    public DataScope scope(String resourceType, String action) {
        if (registry.isUnscoped(resourceType)) {
            return DataScope.all();
        }
        LudwigPrincipal principal = SecurityPrincipals.require();
        return scopeProvider.scopeFor(principal, resourceType, action);
    }

    /**
     * The scope as a predicate to AND into a query.
     *
     * <p>Never {@code null} - an unrestricted caller gets an always-true expression. A caller with no
     * grant at all gets an always-false one rather than an exception, so a list endpoint answers with
     * an empty page: a 403 on a search would tell an unauthorized caller that matching rows exist.
     */
    public <T> BooleanExpression predicate(String resourceType, String action, DataScopeMapping<T> mapping) {
        DataScope scope = scope(resourceType, action);
        metrics.recordDataScopeApplied(resourceType, scope.access().name());
        return predicateFactory.toPredicate(scope, mapping);
    }

    /**
     * Same, resolving the mapping from the registry by resource name.
     *
     * <p>Explicitly unscoped resources are answered before the registry is consulted at all: they have
     * no mapping by definition, so requiring one here would make {@code unscoped-resources} work through
     * {@link #check} and throw through this method - an escape hatch that fails on exactly the endpoints
     * that list rather than load.
     */
    public BooleanExpression predicate(String resourceType, String action) {
        if (registry.isUnscoped(resourceType)) {
            metrics.recordDataScopeApplied(resourceType, DataScope.Access.ALL.name());
            return predicateFactory.unrestricted();
        }
        return predicate(resourceType, action, registry.require(resourceType));
    }

    /**
     * Whether this caller may perform {@code action} on this specific object.
     *
     * <p>Silent - it records nothing. Use it where a denial is an expected branch (deciding whether to
     * show an action in a response), and {@link #check} where a denial is a rejected request.
     */
    public <T> boolean permits(String resourceType, String action, T target) {
        if (registry.isUnscoped(resourceType)) {
            return true;
        }
        DataScopeMapping<T> mapping = registry.require(resourceType);
        return matches(scope(resourceType, action), mapping, target);
    }

    /**
     * Enforces {@link #permits}, auditing and counting the denial.
     *
     * @throws AccessDeniedException when the caller's scope does not cover this object
     */
    public <T> void check(String resourceType, String action, T target) {
        check(resourceType, action, target, unused -> null);
    }

    /**
     * @param idExtractor reads the object's id purely for the audit record, so the trail says which
     *                    row was refused rather than only that something was
     */
    public <T> void check(String resourceType, String action, T target, Function<T, Object> idExtractor) {
        if (registry.isUnscoped(resourceType)) {
            return;
        }
        LudwigPrincipal principal = SecurityPrincipals.require();
        DataScopeMapping<T> mapping = registry.require(resourceType);
        DataScope scope = scopeProvider.scopeFor(principal, resourceType, action);

        if (matches(scope, mapping, target)) {
            auditLogger.record(decision(principal, resourceType, action, target, idExtractor, scope, true, null));
            return;
        }

        metrics.recordAccessDenied(resourceType, action);
        auditLogger.record(decision(principal, resourceType, action, target, idExtractor, scope, false,
                "out-of-scope"));
        // The message is intentionally free of the object's identifiers: it reaches the client, and
        // "you may not read order 42" confirms order 42 exists.
        throw new AccessDeniedException("Access to this " + resourceType + " is not permitted");
    }

    private <T> boolean matches(DataScope scope, DataScopeMapping<T> mapping, T target) {
        if (scope.isUnrestricted()) {
            return true;
        }
        if (scope.denies() || target == null) {
            return false;
        }
        return scope.alternatives().stream().anyMatch(restriction ->
                restriction.values().entrySet().stream().allMatch(entry -> mapping.binding(entry.getKey())
                        .map(binding -> binding.matches(target, entry.getValue()))
                        // An unbound dimension cannot be verified on the object, so it cannot be
                        // treated as satisfied - same fail-closed rule as the predicate side.
                        .orElse(false)));
    }

    private <T> AccessDecision decision(LudwigPrincipal principal, String resourceType, String action,
                                        T target, Function<T, Object> idExtractor, DataScope scope,
                                        boolean granted, String reason) {
        Object id = target == null ? null : idExtractor.apply(target);
        return AccessDecision.builder()
                .subject(principal.subject())
                .principalType(principal.type())
                .resourceType(resourceType)
                .action(action)
                .resourceId(id == null ? null : id.toString())
                .scopeAccess(scope.access().name())
                .granted(granted)
                .reason(reason)
                .build();
    }
}
