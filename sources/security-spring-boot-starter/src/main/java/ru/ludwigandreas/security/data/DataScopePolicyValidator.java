package ru.ludwigandreas.security.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Checks, at startup, that the configured policies and the registered mappings describe the same world.
 *
 * <p>Every problem it finds would otherwise surface as an exception thrown from the first request that
 * happened to exercise that particular resource, action and role - which is to say, from production,
 * intermittently, on the endpoint nobody tested with that role. Moving the check to context startup
 * turns all of it into a failed deploy, which is the whole point.
 *
 * <p>It reports <em>every</em> problem it finds rather than stopping at the first: fixing a policy tree
 * one startup failure at a time is a slow and demoralizing way to find out there were four.
 */
@Slf4j
public class DataScopePolicyValidator implements InitializingBean {

    /** The resource-type prefix of an administrative area's objects, as in {@code admin.reconciliation}. */
    public static final String ADMIN_RESOURCE_PREFIX = "admin.";

    private final ScopePolicies policies;
    private final DataScopeRegistry registry;
    private final Supplier<Set<String>> administrativeAreas;

    private static final List<String> ADMIN_TIERS = List.of("read", "operate", "privileged");

    /**
     * Creates the validator for a service with no administrative surface: any {@code admin.*} policy is refused.
     *
     * @param policies the compiled policies
     * @param registry the resource mappings
     */
    public DataScopePolicyValidator(ScopePolicies policies, DataScopeRegistry registry) {
        this(policies, registry, Set::of);
    }

    /**
     * Creates the validator.
     *
     * @param policies            the compiled policies
     * @param registry            the resource mappings
     * @param administrativeAreas the areas of the administrative surface, read at validation
     */
    public DataScopePolicyValidator(ScopePolicies policies, DataScopeRegistry registry,
                                    Supplier<Set<String>> administrativeAreas) {
        this.policies = policies;
        this.registry = registry;
        this.administrativeAreas = administrativeAreas;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();

        for (String resource : policies.resources()) {
            if (resource.startsWith(ADMIN_RESOURCE_PREFIX)) {
                validateAdministrative(resource, problems);
                continue;
            }
            if (registry.isUnscoped(resource)) {
                // The guard short-circuits unscoped resources to "everything", so these policies would
                // never be consulted. Two settings that contradict each other, one of which silently
                // wins, is exactly the configuration that gets misread in a review.
                problems.add("Resource '" + resource + "' is listed in "
                        + "ludwig.security.data.unscoped-resources but also has policies under "
                        + "ludwig.security.data.policies. An unscoped resource never consults a policy - "
                        + "remove it from one of the two.");
                continue;
            }

            var mapping = registry.find(resource);
            if (mapping.isEmpty()) {
                problems.add("Resource '" + resource + "' has policies but no DataScopeMapping bean. "
                        + "Register one (it is what tells the module which columns carry which "
                        + "dimension), or add the resource to ludwig.security.data.unscoped-resources "
                        + "if it is deliberately exempt.");
                continue;
            }

            Set<ScopeDimension> bound = mapping.get().dimensions();
            String unbound = policies.dimensionsUsedBy(resource).stream()
                    .filter(dimension -> !bound.contains(dimension))
                    .map(ScopeDimension::name)
                    .collect(Collectors.joining(", "));
            if (!unbound.isEmpty()) {
                problems.add("Resource '" + resource + "' has policies using dimension(s) [" + unbound
                        + "] that its DataScopeMapping does not bind (it binds: "
                        + bound.stream().map(ScopeDimension::name).collect(Collectors.joining(", "))
                        + "). An unbound dimension cannot be enforced.");
            }
        }

        if (!problems.isEmpty()) {
            throw new SecurityConfigurationException("Data-scope configuration is inconsistent:"
                    + problems.stream().map(problem -> "\n  - " + problem).collect(Collectors.joining()));
        }

        // Not an error: a mapping with no configured policy is normal when a custom DataScopeProvider
        // drives that resource. Logged so an accidental one is at least visible in the startup log.
        registry.resourceTypes().stream()
                .filter(resource -> !policies.resources().contains(resource))
                .forEach(resource -> log.info("Resource '{}' has a DataScopeMapping but no entry under "
                        + "ludwig.security.data.policies; its scope must come from a DataScopeProvider "
                        + "bean, otherwise every caller is denied.", resource));

        log.debug("Data-scope configuration validated: {} policed resource(s), {} mapped resource(s)",
                policies.resources().size(), registry.resourceTypes().size());
    }

    /**
     * An administrative resource is named objects, not rows: it needs no mapping, but its area must exist, its actions
     * must be tiers, and a role may only keep every object or none - objects are granted per subject.
     */
    private void validateAdministrative(String resource, List<String> problems) {
        String area = resource.substring(ADMIN_RESOURCE_PREFIX.length());
        if (!administrativeAreas.get().contains(area)) {
            problems.add("Resource '" + resource + "' scopes administrative area '" + area + "', which no module"
                    + " of this service declares (or the administrative surface is off). A misspelled area would"
                    + " scope nothing.");
            return;
        }
        for (String action : ADMIN_TIERS) {
            policies.grantsFor(resource, action).forEach((role, grant) -> {
                if (grant.kind() == ScopeGrant.Kind.RESTRICTED) {
                    problems.add("Policy " + resource + "." + action + "." + role + " is '"
                            + grant.dimensions().stream().map(ScopeDimension::name).collect(Collectors.joining("+"))
                            + "', but a role entry for an administrative resource is ALL or NONE: objects are"
                            + " granted per subject (dimension 'object', through the identity grants), not listed"
                            + " per role.");
                }
            });
        }
        policies.actionsOf(resource).stream().filter(action -> !ADMIN_TIERS.contains(action)).forEach(action ->
                problems.add("Policy " + resource + "." + action + " names no tier; the action of an administrative"
                        + " resource is read, operate or privileged."));
    }
}
