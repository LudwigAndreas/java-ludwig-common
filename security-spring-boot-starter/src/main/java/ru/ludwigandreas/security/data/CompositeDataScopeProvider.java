package ru.ludwigandreas.security.data;

import java.util.List;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.security.principal.LudwigPrincipal;

/**
 * Unions every registered {@link DataScopeProvider}, so configuration-driven role defaults and
 * lookup-driven explicit grants can coexist without either knowing about the other.
 *
 * <p>Union, not intersection: providers are independent sources of authority, and each one only knows
 * about the grants it manages. A provider that knows nothing about a caller returns
 * {@link DataScope#none()}, which under intersection would veto every other provider's grant - the
 * whole scheme would collapse to "the least informed source wins".
 *
 * <p>The consequence is that a provider can only ever widen access, never narrow it. Denial has to be
 * expressed by no provider granting, which is also what makes the empty configuration safe.
 */
@RequiredArgsConstructor
public class CompositeDataScopeProvider implements DataScopeProvider {

    private final List<DataScopeProvider> providers;

    @Override
    public DataScope scopeFor(LudwigPrincipal principal, String resourceType, String action) {
        DataScope scope = DataScope.none();
        for (DataScopeProvider provider : providers) {
            scope = scope.union(provider.scopeFor(principal, resourceType, action));
            if (scope.isUnrestricted()) {
                return scope;
            }
        }
        return scope;
    }
}
