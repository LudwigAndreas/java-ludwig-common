package ru.ludwigandreas.security.admin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Which administrative operations need a second person's approval, validated at startup.
 */
public final class AdminApprovalPolicy {

    private final Set<String> required;
    private final Duration ttl;

    /**
     * Validates and creates the policy.
     *
     * @param required       the operation ids that require approval
     * @param ttl            how long an approval may stay undecided
     * @param catalog        the declared operations
     * @param storePresent   whether an approval store exists
     * @throws SecurityConfigurationException when an id is uncatalogued or not privileged, or no store exists
     */
    public AdminApprovalPolicy(List<String> required, Duration ttl, AdminOperationCatalog catalog,
                               boolean storePresent) {
        List<String> problems = new ArrayList<>();
        for (String id : required) {
            catalog.find(id).ifPresentOrElse(operation -> {
                if (operation.tier() != AdminTier.PRIVILEGED) {
                    problems.add("'" + id + "' is a " + operation.tier().token() + " operation; only privileged"
                            + " operations can require approval");
                }
            }, () -> problems.add("'" + id + "' is not an administrative operation of this service"));
        }
        if (!required.isEmpty() && !storePresent) {
            problems.add("approval is required for " + required + " but no AdminApprovalStore exists; add"
                    + " job-core, which provides the JDBC one, or declare a store");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            problems.add("ludwig.admin.approval.ttl must be positive, was " + ttl);
        }
        if (!problems.isEmpty()) {
            throw new SecurityConfigurationException("ludwig.admin.approval is inconsistent:"
                    + problems.stream().map(problem -> "\n  - " + problem).reduce("", String::concat));
        }
        this.required = Set.copyOf(required);
        this.ttl = ttl;
    }

    /**
     * Whether an operation's calls are held for approval.
     *
     * @param operationId the operation
     * @return whether it is listed
     */
    public boolean requires(String operationId) {
        return required.contains(operationId);
    }

    /**
     * Whether any operation requires approval.
     *
     * @return whether the list is non-empty
     */
    public boolean isEnabled() {
        return !required.isEmpty();
    }

    /**
     * How long an approval may stay undecided.
     *
     * @return the time to live
     */
    public Duration ttl() {
        return ttl;
    }
}
