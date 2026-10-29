package ru.ludwigandreas.security.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.data.DataScopeProvider;
import ru.ludwigandreas.security.data.DataScopePolicyValidator;
import ru.ludwigandreas.security.data.ScopeDimension;
import ru.ludwigandreas.security.data.ScopePolicies;
import ru.ludwigandreas.security.principal.LudwigPrincipal;

/**
 * Which named administrative objects a caller's data scope admits - the one place the question is answered, for
 * the guard's check of an {@link AdminObject} and for a listing that must show only the caller's objects.
 *
 * <p>The resource is {@code admin.}<i>area</i>, the action the tier and the dimension {@link ScopeDimension#OBJECT}. An
 * area and tier with no data-scope policy entry is not scoped: holding the operation admits every object.
 */
public class AdminObjectScope {

    private final ScopePolicies policies;
    private final DataScopeProvider provider;

    /**
     * Creates the scope.
     *
     * @param policies the compiled data-scope policies, or null when data scoping is off - nothing is then scoped
     * @param provider the data-scope provider, or null when data scoping is off
     */
    public AdminObjectScope(ScopePolicies policies, DataScopeProvider provider) {
        this.policies = policies;
        this.provider = provider;
    }

    /**
     * Whether an area's tier is scoped by object at all.
     *
     * @param area the administrative area
     * @param tier the tier
     * @return whether the policy has an entry for it
     */
    public boolean isScoped(String area, AdminTier tier) {
        return policies != null && provider != null && !policies.grantsFor(resource(area), tier.token()).isEmpty();
    }

    /**
     * Whether a caller may act on one object.
     *
     * @param principal the caller
     * @param area      the administrative area
     * @param tier      the tier of the operation
     * @param object    the object name
     * @return whether the object is in the caller's scope, always true for an unscoped area and tier
     */
    public boolean admits(LudwigPrincipal principal, String area, AdminTier tier, String object) {
        return !isScoped(area, tier)
                || provider.scopeFor(principal, resource(area), tier.token()).admits(ScopeDimension.OBJECT, object);
    }

    /**
     * The objects of a listing the current administrative caller may see, in their order.
     *
     * @param area  the administrative area
     * @param tier  the tier of the listing's operation
     * @param names every object
     * @return the admitted ones
     */
    public List<String> filter(String area, AdminTier tier, Collection<String> names) {
        if (!isScoped(area, tier)) {
            return List.copyOf(names);
        }
        LudwigPrincipal principal = AdminInvocation.require().principal();
        return names.stream().filter(name -> admits(principal, area, tier, name)).toList();
    }

    private static String resource(String area) {
        return DataScopePolicyValidator.ADMIN_RESOURCE_PREFIX + area;
    }
}
