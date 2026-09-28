package ru.ludwigandreas.identity.authority;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.identity.entity.SecurityGrantEntity;
import ru.ludwigandreas.identity.repository.SecurityGrantRepository;
import ru.ludwigandreas.security.data.DataScope;
import ru.ludwigandreas.security.data.DataScopeProvider;
import ru.ludwigandreas.security.data.ScopeDimension;
import ru.ludwigandreas.security.principal.LudwigPrincipal;

/**
 * Turns explicit grant rows into a {@link DataScope}.
 *
 * <p>Composes with - never replaces - the configuration-driven role policy: the security module unions
 * every provider's answer, so a temporary coverage grant widens what a role already allows without anyone
 * editing the role policy, and expires on its own.
 *
 * <p>Deliberately <em>not</em> cached alongside authorities. Roles are stable enough to cache for a
 * minute; data scope is resolved per resource and per action, is far more likely to be time-boxed, and a
 * stale one is not a failed request but the wrong rows in a response.
 */
@RequiredArgsConstructor
public class DatabaseDataScopeProvider implements DataScopeProvider {

    private final SecurityGrantRepository grants;

    @Override
    @Transactional(readOnly = true)
    public DataScope scopeFor(LudwigPrincipal principal, String resourceType, String action) {
        List<SecurityGrantEntity> active =
                grants.activeGrants(principal.subject(), principal.type(), resourceType, action);

        DataScope scope = DataScope.none();
        for (SecurityGrantEntity grant : active) {
            scope = scope.union(toScope(grant));
            if (scope.isUnrestricted()) {
                return scope;
            }
        }
        return scope;
    }

    /**
     * A grant with a dimension but no value is treated as a denial rather than as "unrestricted". The row
     * is malformed, and the generous reading of a malformed permission row is how a restricted grant
     * becomes a blanket one.
     */
    private DataScope toScope(SecurityGrantEntity grant) {
        if (grant.isUnrestricted()) {
            return DataScope.all();
        }
        if (grant.getDimensionValue() == null || grant.getDimensionValue().isBlank()) {
            return DataScope.none();
        }
        return DataScope.restrictedTo(ScopeDimension.of(grant.getDimension()), grant.getDimensionValue());
    }
}
