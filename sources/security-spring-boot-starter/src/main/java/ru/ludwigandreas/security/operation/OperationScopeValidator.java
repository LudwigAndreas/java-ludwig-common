package ru.ludwigandreas.security.operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.InitializingBean;
import ru.ludwigandreas.security.data.DataScopeMapping;
import ru.ludwigandreas.security.data.DataScopeRegistry;
import ru.ludwigandreas.security.data.ScopeDimension;
import ru.ludwigandreas.security.data.ScopeGrant;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Checks at startup that every scope {@code ludwig.security.roles} grants can actually be enforced.
 *
 * <p>A dimension nothing binds would otherwise fail - or worse, be ignored - on the first request that exercised
 * that role and operation. It reports every problem at once rather than stopping at the first.
 *
 * <ul>
 *   <li>An operation that touches rows: every dimension must be bound by its resource's {@link DataScopeMapping};
 *       an unscoped resource admits only {@code ALL}; with row scoping off, no dimension can be enforced at all.</li>
 *   <li>An operation that touches no rows: only {@code OBJECT}, which the named-object parameter of an
 *       administrative handler binds, and only on the administrative surface.</li>
 * </ul>
 */
public class OperationScopeValidator implements InitializingBean {

    private final OperationGrants grants;
    private final DataScopeRegistry registry;

    /**
     * Creates the validator.
     *
     * @param grants   the compiled role table
     * @param registry the resource mappings, or null when row scoping is off
     */
    public OperationScopeValidator(OperationGrants grants, DataScopeRegistry registry) {
        this.grants = grants;
        this.registry = registry;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();
        grants.all().forEach((operationId, holders) -> {
            Operation operation = grants.catalog().find(operationId).orElseThrow();
            for (OperationGrants.Holder holder : holders) {
                if (holder.via() == null && holder.grant().kind() == ScopeGrant.Kind.RESTRICTED) {
                    check(operation, holder, problems);
                }
            }
        });
        if (!problems.isEmpty()) {
            throw new SecurityConfigurationException("ludwig.security.roles grants scopes that cannot be enforced:"
                    + "\n  - "
                    + String.join("\n  - ", problems));
        }
    }

    private void check(Operation operation, OperationGrants.Holder holder, List<String> problems) {
        String entry = holder.role() + " -> " + operation.id() + ": " + names(Set.copyOf(holder.grant().dimensions()));
        Set<ScopeDimension> used = Set.copyOf(holder.grant().dimensions());
        if (!operation.touchesRows()) {
            if (!Set.of(ScopeDimension.OBJECT).containsAll(used)) {
                problems.add(entry + " - " + operation.id() + " touches no rows, so it admits only ALL or OBJECT");
            } else if (operation.surface() != Surface.ADMIN) {
                problems.add(entry + " - OBJECT is bound by an administrative handler's named object, and "
                        + operation.id() + " is not administrative");
            }
            return;
        }
        if (registry == null) {
            problems.add(entry + " - row scoping is off (ludwig.security.data.enabled=false), so no dimension can be"
                    + " enforced");
            return;
        }
        if (registry.isUnscoped(operation.resource())) {
            problems.add(entry + " - resource '" + operation.resource() + "' is in ludwig.security.data"
                    + ".unscoped-resources, so it admits only ALL");
            return;
        }
        Optional<DataScopeMapping<?>> mapping = registry.find(operation.resource());
        if (mapping.isEmpty()) {
            problems.add(entry + " - resource '" + operation.resource() + "' has no DataScopeMapping bean");
            return;
        }
        Set<ScopeDimension> bound = mapping.get().dimensions();
        Set<ScopeDimension> unbound = used.stream()
                .filter(dimension -> !bound.contains(dimension)
                        && !(ScopeDimension.OBJECT.equals(dimension) && operation.surface() == Surface.ADMIN))
                .collect(Collectors.toSet());
        if (!unbound.isEmpty()) {
            problems.add(entry + " - resource '" + operation.resource() + "' does not bind " + names(unbound)
                    + " (it binds " + names(bound) + ")");
        }
    }

    private static String names(Set<ScopeDimension> dimensions) {
        return dimensions.stream().map(ScopeDimension::name).sorted().collect(Collectors.joining(", "));
    }
}
