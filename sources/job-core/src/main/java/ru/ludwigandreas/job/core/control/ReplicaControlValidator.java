package ru.ludwigandreas.job.core.control;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Checks the replica controls at startup: unique lower-case names, and every operation a control may declare present
 * in the catalog - a control whose operation nobody can hold would be refused to everyone at runtime instead.
 */
public final class ReplicaControlValidator {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*");

    private ReplicaControlValidator() {
    }

    /**
     * Validates the controls and indexes them by name.
     *
     * @param controls the declared controls
     * @param catalog  the administrative operations
     * @return the controls by name
     * @throws SecurityConfigurationException naming every problem
     */
    public static Map<String, ReplicaControl<?>> index(List<ReplicaControl<?>> controls,
                                                       AdminOperationCatalog catalog) {
        Map<String, ReplicaControl<?>> byName = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (ReplicaControl<?> control : controls) {
            if (control.name() == null || !NAME.matcher(control.name()).matches()) {
                problems.add("control name '" + control.name() + "' is not a lower-case word");
            }
            if (byName.put(control.name(), control) != null) {
                problems.add("two controls are named '" + control.name() + "'");
            }
            for (AdminOperation operation : control.possibleOperations()) {
                if (!catalog.contains(operation.id())) {
                    problems.add("control '" + control.name() + "' may declare " + operation.id()
                            + ", which no module of this service catalogs");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new SecurityConfigurationException("Replica controls are inconsistent:"
                    + problems.stream().map(problem -> "\n  - " + problem).reduce("", String::concat));
        }
        return byName;
    }
}
