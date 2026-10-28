package ru.ludwigandreas.security.admin;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;
import ru.ludwigandreas.security.principal.LudwigPrincipal;

/**
 * Which of this service's roles hold which administrative operation, validated against the catalog.
 *
 * <p>Every gap fails closed. A key that names no catalogued operation, an empty role list and a blank role all
 * fail startup, because each of them is a grant somebody meant to write and did not - and the alternative is a
 * permanent {@code 403} that reads as a permissions problem and gets "fixed" by granting more. An operation with
 * no entry is held by nobody, and the operations in that state are logged once at startup.
 */
@Slf4j
public final class AdminGrants {

    private final AdminOperationCatalog catalog;
    private final Map<String, Set<String>> rolesByOperation;

    /**
     * Validates and normalizes the configured grants.
     *
     * @param configured operation id to role names, as bound from {@code ludwig.admin.grants}
     * @param catalog    the operations the modules in this service declare
     * @throws SecurityConfigurationException on an uncatalogued key, an empty list or a blank role
     */
    public AdminGrants(Map<String, List<String>> configured, AdminOperationCatalog catalog) {
        this.catalog = catalog;
        Map<String, Set<String>> normalized = new TreeMap<>();
        (configured == null ? Map.<String, List<String>>of() : configured).forEach((id, roles) -> {
            if (!catalog.contains(id)) {
                String area = id.contains(".") ? id.substring(0, id.indexOf('.')) : id;
                throw new SecurityConfigurationException("ludwig.admin.grants names '" + id + "', which no module"
                        + " in this service declares. Operations of area '" + area + "': " + catalog.idsOf(area)
                        + ". In a .properties file a dotted key needs the bracket form,"
                        + " ludwig.admin.grants[" + id + "]=...");
            }
            if (roles == null || roles.isEmpty()) {
                throw new SecurityConfigurationException("ludwig.admin.grants." + id + " lists no role. Remove the"
                        + " entry to grant the operation to nobody, or name the roles that hold it.");
            }
            Set<String> prefixed = new LinkedHashSet<>();
            for (String role : roles) {
                if (role == null || role.isBlank()) {
                    throw new SecurityConfigurationException("ludwig.admin.grants." + id + " contains a blank role");
                }
                prefixed.add(Authorities.normalizeRole(role));
            }
            normalized.put(id, Collections.unmodifiableSet(prefixed));
        });
        this.rolesByOperation = Map.copyOf(normalized);

        List<String> ungranted = ungranted();
        if (!ungranted.isEmpty()) {
            log.info("Administrative operations no role of this service holds, and which every caller is therefore"
                    + " refused: {}. Grant them under ludwig.admin.grants.", ungranted);
        }
    }

    /**
     * Whether the principal holds the operation.
     *
     * <p>Decided on the principal's resolved roles only - the roles the authority resolver answered with, after
     * any personal-access-token attenuation. A role carried as a claim in the token is not on the principal and
     * therefore cannot satisfy a grant.
     *
     * @param principal   the caller
     * @param operationId the operation
     * @return true when at least one of the caller's roles is granted the operation
     */
    public boolean holds(LudwigPrincipal principal, String operationId) {
        Set<String> granted = rolesByOperation.get(operationId);
        return granted != null && principal.roles().stream().anyMatch(granted::contains);
    }

    /**
     * The catalogued operations the principal holds.
     *
     * @param principal the caller
     * @return the operations, ordered by id
     */
    public List<AdminOperation> heldBy(LudwigPrincipal principal) {
        return catalog.all().stream().filter(operation -> holds(principal, operation.id())).toList();
    }

    /**
     * The catalogued operations no role holds.
     *
     * @return their ids, ordered
     */
    public List<String> ungranted() {
        return catalog.all().stream().map(AdminOperation::id).filter(id -> !rolesByOperation.containsKey(id)).toList();
    }

    /**
     * The operations this service declares.
     *
     * @return the catalog the grants were validated against
     */
    public AdminOperationCatalog catalog() {
        return catalog;
    }
}
