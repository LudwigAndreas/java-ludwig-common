package ru.ludwigandreas.audit;

import java.util.HashMap;
import java.util.Map;

/**
 * Decides, per event, whether a sink failure fails the caller.
 *
 * <p>Resolved per event rather than fixed per sink because the same sink carries both kinds of event:
 * {@code access.denied} and {@code setting.changed} go to the same {@code audit_event} table, and the
 * first must not be able to fail a request while the second must.
 */
@FunctionalInterface
public interface AuditFailurePolicyResolver {

    /**
     * The policy for one event.
     *
     * @param event the event whose write failed, or is about to
     * @return what to do about the failure
     */
    AuditFailurePolicy policyFor(AuditEvent event);

    /** A resolver that applies one policy to everything, for a test or a deliberate override. */
    static AuditFailurePolicyResolver always(AuditFailurePolicy policy) {
        return event -> policy;
    }

    /**
     * The platform default: state mutations fail the operation, everything else is logged and dropped.
     *
     * <p>Only {@link AuditCategories#SETTINGS} is a mutation category today, and it is the one the
     * argument was made about: it is the only subsystem whose audit row is already written inside the
     * caller's transaction, so rethrowing actually rolls the change back rather than merely reporting
     * a change that stands. Adding a category here without also putting its audit write in the
     * caller's transaction would produce an error on a change that happened anyway, which is worse
     * than either policy - see {@code audit-spring-boot-starter}'s README.
     *
     * @return a resolver over the platform's own categories
     */
    static AuditFailurePolicyResolver platformDefault() {
        return ofCategories(Map.of(AuditCategories.SETTINGS, AuditFailurePolicy.FAIL_OPERATION),
                AuditFailurePolicy.LOG_AND_CONTINUE);
    }

    /**
     * A resolver over a category-to-policy map.
     *
     * @param byCategory the per-category policies
     * @param fallback   the policy for a category the map does not name
     * @return the resolver
     */
    static AuditFailurePolicyResolver ofCategories(Map<String, AuditFailurePolicy> byCategory,
                                                   AuditFailurePolicy fallback) {
        Map<String, AuditFailurePolicy> copy = new HashMap<>(byCategory == null ? Map.of() : byCategory);
        AuditFailurePolicy effectiveFallback =
                fallback == null ? AuditFailurePolicy.LOG_AND_CONTINUE : fallback;
        return event -> copy.getOrDefault(event.category(), effectiveFallback);
    }
}
