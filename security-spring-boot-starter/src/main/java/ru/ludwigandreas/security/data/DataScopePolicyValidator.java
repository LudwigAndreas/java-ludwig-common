package ru.ludwigandreas.security.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class DataScopePolicyValidator implements InitializingBean {

    private final ScopePolicies policies;
    private final DataScopeRegistry registry;

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();

        for (String resource : policies.resources()) {
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
}
