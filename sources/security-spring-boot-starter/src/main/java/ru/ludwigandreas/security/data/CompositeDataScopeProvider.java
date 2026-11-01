package ru.ludwigandreas.security.data;

import java.util.List;
import java.util.function.Supplier;
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
public class CompositeDataScopeProvider implements DataScopeProvider {

    private final Supplier<List<DataScopeProvider>> resolve;
    private volatile List<DataScopeProvider> providers;

    /**
     * Unites a fixed list of providers.
     *
     * @param providers the providers
     */
    public CompositeDataScopeProvider(List<DataScopeProvider> providers) {
        this(() -> providers);
    }

    /**
     * Unites the providers the supplier answers on first use - resolved then, rather than at creation, so that the
     * composite can exist before them and leave itself out.
     *
     * @param resolve every provider in the context
     */
    public CompositeDataScopeProvider(Supplier<List<DataScopeProvider>> resolve) {
        this.resolve = resolve;
    }

    @Override
    public DataScope scopeFor(LudwigPrincipal principal, String resourceType, String action) {
        DataScope scope = DataScope.none();
        for (DataScopeProvider provider : providers()) {
            scope = scope.union(provider.scopeFor(principal, resourceType, action));
            if (scope.isUnrestricted()) {
                return scope;
            }
        }
        return scope;
    }

    private List<DataScopeProvider> providers() {
        List<DataScopeProvider> resolved = providers;
        if (resolved == null) {
            resolved = resolve.get().stream().filter(provider -> provider != this).toList();
            providers = resolved;
        }
        return resolved;
    }
}
