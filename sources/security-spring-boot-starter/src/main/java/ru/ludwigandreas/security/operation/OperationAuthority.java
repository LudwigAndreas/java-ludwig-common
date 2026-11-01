package ru.ludwigandreas.security.operation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.security.data.DataScope;
import ru.ludwigandreas.security.data.DataScopeProvider;
import ru.ludwigandreas.security.data.ScopeDimension;
import ru.ludwigandreas.security.data.ScopeGrant;
import ru.ludwigandreas.security.data.ScopeLookup;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * What a caller may do: whether it holds an operation, and with which row scope.
 *
 * <p>The only place a caller's roles are compared with role names. The scope is the union of the caller's roles'
 * grants in {@code ludwig.security.roles} and of every contributed {@link DataScopeProvider} - per-subject grants
 * such as the identity projection's - asked for the operation's resource and action. Holding an operation is
 * holding a scope other than none.
 */
@Slf4j
public class OperationAuthority implements ScopeLookup {

    private final OperationGrants grants;
    private final Supplier<DataScopeProvider> contributed;

    /**
     * Creates the authority.
     *
     * @param grants      the compiled role table
     * @param contributed the contributed per-subject providers, united; may answer null when there are none
     */
    public OperationAuthority(OperationGrants grants, Supplier<DataScopeProvider> contributed) {
        this.grants = grants;
        this.contributed = contributed;
    }

    /**
     * The catalogued operation with this id.
     *
     * @param operationId an id
     * @return the operation
     * @throws IllegalStateException when no module declares it
     */
    public Operation require(String operationId) {
        return grants.catalog().find(operationId).orElseThrow(() -> new IllegalStateException(
                "Operation '" + operationId + "' is not catalogued"));
    }

    @Override
    public String resourceOf(String operationId) {
        Operation operation = require(operationId);
        if (!operation.touchesRows()) {
            throw new IllegalStateException("Operation '" + operationId + "' touches no rows, so it has no row"
                    + " scope; declare its resource with Operation.on(resource, action)");
        }
        return operation.resource();
    }

    @Override
    public DataScope scopeFor(LudwigPrincipal principal, String operationId) {
        return scope(principal, require(operationId));
    }

    /**
     * The caller's scope for an operation.
     *
     * @param principal the caller
     * @param operation the operation
     * @return the scope; none when the caller does not hold it
     */
    public DataScope scope(LudwigPrincipal principal, Operation operation) {
        DataScope scope = DataScope.none();
        for (String role : principal.roles()) {
            for (ScopeGrant grant : grants.grantsOf(role).getOrDefault(operation.id(), List.of())) {
                scope = scope.union(toScope(principal, grant, operation));
                if (scope.isUnrestricted()) {
                    return scope;
                }
            }
        }
        DataScopeProvider provider = contributed.get();
        if (provider != null) {
            scope = scope.union(provider.scopeFor(principal, operation.scopeResource(), operation.scopeAction()));
        }
        return scope;
    }

    /**
     * Whether the caller holds an operation.
     *
     * @param principal   the caller
     * @param operationId the operation
     * @return true when its scope is not none
     */
    public boolean holds(LudwigPrincipal principal, String operationId) {
        return grants.catalog().contains(operationId) && !scopeFor(principal, operationId).denies();
    }

    /**
     * Every operation the caller holds, with its scope.
     *
     * @param principal the caller
     * @return operation to scope, ordered by id
     */
    public Map<Operation, DataScope> heldBy(LudwigPrincipal principal) {
        Map<Operation, DataScope> held = new LinkedHashMap<>();
        for (Operation operation : grants.catalog().all()) {
            DataScope scope = scope(principal, operation);
            if (!scope.denies()) {
                held.put(operation, scope);
            }
        }
        return held;
    }

    /**
     * Whether the caller's scope for an operation confines it to named objects - every alternative restricts
     * {@code OBJECT}. An unrestricted scope, or one restricted on another dimension only, confines no object.
     *
     * @param scope a caller's scope
     * @return whether named objects must each be admitted
     */
    public static boolean confinesObjects(DataScope scope) {
        return !scope.isUnrestricted() && !scope.alternatives().isEmpty() && scope.alternatives().stream()
                .allMatch(alternative -> alternative.values().containsKey(ScopeDimension.OBJECT));
    }

    /**
     * The named objects the caller may act on through an operation, for a listing of them: all of them where its
     * scope confines no object, else only those its {@code OBJECT} grants admit.
     *
     * @param principal   the caller
     * @param operationId the operation
     * @param objects     the objects' names, in listing order
     * @return the admitted ones, in the same order
     */
    public List<String> admitted(LudwigPrincipal principal, String operationId, Collection<String> objects) {
        DataScope scope = scopeFor(principal, operationId);
        if (!confinesObjects(scope)) {
            return scope.denies() ? List.of() : List.copyOf(objects);
        }
        return objects.stream().filter(object -> scope.admits(ScopeDimension.OBJECT, object)).toList();
    }

    /**
     * The compiled role table.
     *
     * @return it
     */
    public OperationGrants grants() {
        return grants;
    }

    /**
     * A dimension the principal has no value for collapses the whole grant to none rather than being dropped from
     * the conjunction: dropping it would widen the grant, so a token with no tenant claim would turn "my tenant's
     * rows" into "all rows". {@code OBJECT} has no principal value at all - objects are granted per subject - so a role
     * grant of it contributes nothing on its own.
     */
    private static DataScope toScope(LudwigPrincipal principal, ScopeGrant grant, Operation operation) {
        if (grant.kind() == ScopeGrant.Kind.ALL) {
            return DataScope.all();
        }
        if (grant.kind() == ScopeGrant.Kind.NONE) {
            return DataScope.none();
        }
        Map<ScopeDimension, Set<String>> restriction = new LinkedHashMap<>();
        for (ScopeDimension dimension : grant.dimensions()) {
            Optional<String> value = valueFor(principal, dimension);
            if (value.isEmpty()) {
                log.debug("Principal {} has no value for scope dimension '{}' of operation {}; denying instead of"
                        + " widening the grant", principal.type(), dimension, operation.id());
                return DataScope.none();
            }
            restriction.put(dimension, Set.of(value.get()));
        }
        return restriction.isEmpty() ? DataScope.none() : DataScope.restrictedTo(restriction);
    }

    private static Optional<String> valueFor(LudwigPrincipal principal, ScopeDimension dimension) {
        if (ScopeDimension.OWNER.equals(dimension)) {
            return Optional.of(principal.subject());
        }
        if (ScopeDimension.TENANT.equals(dimension)) {
            return principal.tenant();
        }
        if (ScopeDimension.PARTNER.equals(dimension)) {
            // A partner calling on its own behalf is its own partner id; a user acting for a partner carries it as
            // a grant attribute put there by the AuthorityResolver, never by the client.
            return principal.isType(PrincipalType.PARTNER)
                    ? Optional.of(principal.subject())
                    : principal.attribute(ScopeDimension.PARTNER.name());
        }
        if (ScopeDimension.OBJECT.equals(dimension)) {
            return Optional.empty();
        }
        return principal.attribute(dimension.name());
    }
}
