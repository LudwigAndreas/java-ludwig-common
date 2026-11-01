package ru.ludwigandreas.security.operation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Every operation the modules in this service perform, on every surface.
 *
 * <p>Built once from every {@link OperationContributor} and validated as a whole: an id declared twice fails startup,
 * naming both declarers. The catalog is what enforcement, row scope, discovery and the audit trail all read, which is
 * why none of them can disagree with another.
 */
public final class OperationCatalog {

    private final Map<String, Operation> operations;

    /**
     * Collects and validates the declared operations.
     *
     * @param contributors every contributor in the context
     * @throws SecurityConfigurationException when an id is declared twice
     */
    public OperationCatalog(Collection<? extends OperationContributor> contributors) {
        Map<String, Operation> collected = new TreeMap<>();
        Map<String, String> declaredBy = new LinkedHashMap<>();
        for (OperationContributor contributor : contributors) {
            String name = contributor.getClass().getName();
            for (Operation operation : contributor.operations()) {
                String previous = declaredBy.putIfAbsent(operation.id(), name);
                if (previous != null) {
                    throw new SecurityConfigurationException("Operation '" + operation.id() + "' is declared twice,"
                            + " by " + previous + " and by " + name + ". An operation id names exactly one thing a"
                            + " caller can do.");
                }
                collected.put(operation.id(), operation);
            }
        }
        this.operations = Map.copyOf(collected);
    }

    /**
     * The operation with this id.
     *
     * @param id an operation id
     * @return it, or empty when no module declares it
     */
    public Optional<Operation> find(String id) {
        return Optional.ofNullable(operations.get(id));
    }

    /**
     * Whether a module declares this id.
     *
     * @param id an operation id
     * @return true when catalogued
     */
    public boolean contains(String id) {
        return operations.containsKey(id);
    }

    /**
     * Every catalogued operation, ordered by id.
     *
     * @return them
     */
    public List<Operation> all() {
        return operations.values().stream().sorted((a, b) -> a.id().compareTo(b.id())).toList();
    }

    /**
     * The catalogued ids of one area, for an error message that has to say what was meant.
     *
     * @param area an area
     * @return its ids, ordered, possibly empty
     */
    public List<String> idsOf(String area) {
        return all().stream().filter(operation -> operation.area().equals(area)).map(Operation::id).toList();
    }
}
