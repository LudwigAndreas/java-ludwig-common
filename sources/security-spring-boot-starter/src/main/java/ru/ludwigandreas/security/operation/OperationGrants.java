package ru.ludwigandreas.security.operation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.config.SecurityProperties;
import ru.ludwigandreas.security.data.ScopeGrant;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * {@code ludwig.security.roles}, compiled once at startup: which role holds which operation with which scope.
 *
 * <p>Roles are defined here and nowhere else - code names operations. Every gap fails closed: an operation id no
 * module declares, a {@code NONE} or wildcard entry, an {@code includes} of an undefined role, and an {@code includes}
 * cycle all fail startup, naming the entry. An operation no role holds is refused to everyone and logged once.
 *
 * <p>Two indexes, built once: role to its effective grants (its own and every included role's), which enforcement
 * and discovery read; and operation to the roles holding it, which answers "who may do this?".
 */
@Slf4j
public final class OperationGrants {

    private static final String PREFIX = "ludwig.security.roles.";

    /**
     * One role's hold on an operation.
     *
     * @param role  the role, prefixed
     * @param grant its scope
     * @param via   the role the grant was declared on when it was inherited through {@code includes}, else null
     */
    public record Holder(String role, ScopeGrant grant, String via) {
    }

    private final OperationCatalog catalog;
    private final Map<String, Map<String, List<ScopeGrant>>> byRole;
    private final Map<String, List<Holder>> byOperation;

    private OperationGrants(OperationCatalog catalog, Map<String, Map<String, List<ScopeGrant>>> byRole,
                            Map<String, List<Holder>> byOperation) {
        this.catalog = catalog;
        this.byRole = byRole;
        this.byOperation = byOperation;
    }

    /**
     * Validates and compiles the configured roles.
     *
     * @param roles   role name to definition, as bound from {@code ludwig.security.roles}
     * @param catalog the operations this service's modules declare
     * @return the compiled grants
     * @throws SecurityConfigurationException on any entry that cannot mean what it says
     */
    public static OperationGrants compile(Map<String, SecurityProperties.Role> roles, OperationCatalog catalog) {
        Map<String, SecurityProperties.Role> defined = normalize(roles == null ? Map.of() : roles);
        Map<String, Map<String, ScopeGrant>> own = new LinkedHashMap<>();
        defined.forEach((role, definition) -> own.put(role, parse(role, definition, catalog)));
        Map<String, Map<String, List<ScopeGrant>>> byRole = new TreeMap<>();
        Map<String, List<Holder>> byOperation = new TreeMap<>();
        for (String role : defined.keySet()) {
            Map<String, List<ScopeGrant>> effective = new TreeMap<>();
            for (String source : closure(role, defined)) {
                own.get(source).forEach((operation, grant) -> {
                    effective.computeIfAbsent(operation, id -> new ArrayList<>()).add(grant);
                    byOperation.computeIfAbsent(operation, id -> new ArrayList<>())
                            .add(new Holder(role, grant, source.equals(role) ? null : source));
                });
            }
            byRole.put(role, Collections.unmodifiableMap(effective));
        }
        OperationGrants grants = new OperationGrants(catalog, Collections.unmodifiableMap(byRole),
                Collections.unmodifiableMap(byOperation));
        List<String> unheld = grants.unheld();
        if (!unheld.isEmpty()) {
            log.warn("Operations no role of this service holds, and which every caller is therefore refused: {}."
                    + " Grant them under ludwig.security.roles.", unheld);
        }
        return grants;
    }

    private static Map<String, SecurityProperties.Role> normalize(Map<String, SecurityProperties.Role> roles) {
        Map<String, SecurityProperties.Role> normalized = new LinkedHashMap<>();
        roles.forEach((name, definition) -> {
            if (name == null || name.isBlank()) {
                throw new SecurityConfigurationException("ludwig.security.roles has a role with a blank name");
            }
            String role = Authorities.normalizeRole(name);
            if (normalized.put(role, definition == null ? new SecurityProperties.Role() : definition) != null) {
                throw new SecurityConfigurationException(PREFIX + name + " defines " + role + " a second time; a"
                        + " role is written once, with or without its ROLE_ prefix");
            }
        });
        return normalized;
    }

    private static Map<String, ScopeGrant> parse(String role, SecurityProperties.Role definition,
                                                 OperationCatalog catalog) {
        Map<String, ScopeGrant> parsed = new TreeMap<>();
        definition.getOperations().forEach((operation, raw) -> {
            String entry = PREFIX + role + ".operations." + operation;
            if (operation.contains("*")) {
                throw new SecurityConfigurationException(entry + " is a wildcard. A role names each operation it"
                        + " holds, so that a later release adding an operation never grants it silently.");
            }
            if (!catalog.contains(operation)) {
                String area = operation.contains(".") ? operation.substring(0, operation.indexOf('.')) : operation;
                throw new SecurityConfigurationException(entry + " names an operation no module in this service"
                        + " declares. Operations of area '" + area + "': " + catalog.idsOf(area)
                        + ". In a .properties file a dotted key needs the bracket form, operations[" + operation
                        + "]=...");
            }
            ScopeGrant grant = ScopeGrant.parse(raw, false, entry);
            if (grant.kind() == ScopeGrant.Kind.NONE) {
                throw new SecurityConfigurationException(entry + " is NONE. Leave the operation out instead: a role"
                        + " holds only what it lists.");
            }
            parsed.put(operation, grant);
        });
        return Collections.unmodifiableMap(parsed);
    }

    /** The role and every role it includes, transitively, refusing a cycle and an undefined include. */
    private static Set<String> closure(String role, Map<String, SecurityProperties.Role> defined) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<List<String>> paths = new ArrayDeque<>();
        paths.push(List.of(role));
        while (!paths.isEmpty()) {
            List<String> path = paths.pop();
            String current = path.get(path.size() - 1);
            seen.add(current);
            for (String included : defined.get(current).getIncludes()) {
                String next = Authorities.normalizeRole(included);
                if (!defined.containsKey(next)) {
                    throw new SecurityConfigurationException(PREFIX + current + ".includes names " + next
                            + ", which is not defined under ludwig.security.roles");
                }
                if (path.contains(next)) {
                    List<String> cycle = new ArrayList<>(path.subList(path.indexOf(next), path.size()));
                    cycle.add(next);
                    throw new SecurityConfigurationException("ludwig.security.roles includes a cycle: "
                            + String.join(" -> ", cycle));
                }
                if (!seen.contains(next)) {
                    List<String> extended = new ArrayList<>(path);
                    extended.add(next);
                    paths.push(extended);
                }
            }
        }
        return seen;
    }

    /**
     * A role's effective grants: its own, and those of every role it includes.
     *
     * @param role a role name, with or without its prefix
     * @return operation id to the grants that apply, empty for a role not defined here
     */
    public Map<String, List<ScopeGrant>> grantsOf(String role) {
        return byRole.getOrDefault(Authorities.normalizeRole(role), Map.of());
    }

    /**
     * The roles holding an operation.
     *
     * @param operationId the operation
     * @return each role with its scope and, when inherited, the role it came from
     */
    public List<Holder> holders(String operationId) {
        return byOperation.getOrDefault(operationId, List.of());
    }

    /**
     * Every role grant, for the startup check that each dimension is enforceable.
     *
     * @return operation id to its holders
     */
    public Map<String, List<Holder>> all() {
        return byOperation;
    }

    /**
     * The catalogued operations no role holds.
     *
     * @return their ids, ordered
     */
    public List<String> unheld() {
        return catalog.all().stream().map(Operation::id).filter(id -> !byOperation.containsKey(id)).toList();
    }

    /**
     * The operations this service declares.
     *
     * @return the catalog the grants were validated against
     */
    public OperationCatalog catalog() {
        return catalog;
    }
}
