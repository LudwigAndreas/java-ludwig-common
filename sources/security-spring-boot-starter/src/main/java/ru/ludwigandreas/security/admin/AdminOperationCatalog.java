package ru.ludwigandreas.security.admin;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Every administrative operation the modules in this service offer.
 *
 * <p>Built once, from every {@link AdminOperationContributor} in the context, and validated as a whole: an id
 * declared twice and an area missing one of its three tiers both fail startup, naming what is wrong. A
 * misconfigured catalog is a deployment error, and discovering it as a {@code 403} in production is the outcome
 * this class exists to prevent.
 */
public final class AdminOperationCatalog {

    private final Map<String, AdminOperation> operations;

    /**
     * Collects and validates the declared operations.
     *
     * @param contributors every contributor in the context
     * @throws SecurityConfigurationException when an id is declared twice or an area lacks a tier
     */
    public AdminOperationCatalog(Collection<? extends AdminOperationContributor> contributors) {
        Map<String, AdminOperation> collected = new TreeMap<>();
        Map<String, String> declaredBy = new LinkedHashMap<>();
        for (AdminOperationContributor contributor : contributors) {
            String name = contributor.getClass().getName();
            for (AdminOperation operation : contributor.adminOperations()) {
                String previous = declaredBy.putIfAbsent(operation.id(), name);
                if (previous != null) {
                    throw new SecurityConfigurationException("Administrative operation '" + operation.id()
                            + "' is declared twice, by " + previous + " and by " + name
                            + ". An operation id names exactly one thing a service can grant.");
                }
                collected.put(operation.id(), operation);
            }
        }
        requireEveryTier(collected.values());
        this.operations = Map.copyOf(collected);
    }

    private static void requireEveryTier(Collection<AdminOperation> operations) {
        Map<String, Set<AdminTier>> tiersByArea = new TreeMap<>();
        operations.forEach(operation -> tiersByArea
                .computeIfAbsent(operation.area(), area -> EnumSet.noneOf(AdminTier.class))
                .add(operation.tier()));
        tiersByArea.forEach((area, tiers) -> {
            Set<AdminTier> missing = EnumSet.complementOf(EnumSet.copyOf(tiers));
            if (!missing.isEmpty()) {
                throw new SecurityConfigurationException("Administrative area '" + area + "' does not declare "
                        + missing.stream().map(tier -> area + "." + tier.token()).collect(Collectors.joining(", "))
                        + ". An area declares all three tiers, even one that guards no endpoint yet, so that a"
                        + " grant block naming it does not fail when a later release adds one.");
            }
        });
    }

    /**
     * The operation with this id.
     *
     * @param id an operation id
     * @return the operation, or empty when no module declares it
     */
    public Optional<AdminOperation> find(String id) {
        return Optional.ofNullable(operations.get(id));
    }

    /**
     * Whether a module declares this id.
     *
     * @param id an operation id
     * @return true when the id is catalogued
     */
    public boolean contains(String id) {
        return operations.containsKey(id);
    }

    /**
     * Every catalogued operation, ordered by id.
     *
     * @return the operations
     */
    public List<AdminOperation> all() {
        return operations.values().stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
    }

    /**
     * The catalogued ids of one area, for an error message that has to say what was meant.
     *
     * @param area an area name
     * @return the ids of that area, ordered, possibly empty
     */
    public List<String> idsOf(String area) {
        return all().stream().filter(operation -> operation.area().equals(area)).map(AdminOperation::id).toList();
    }
}
