package ru.ludwigandreas.security.data;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import java.util.Map;
import java.util.Set;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Compiles a {@link DataScope} into a QueryDSL {@link BooleanExpression} against a resource's
 * {@link DataScopeMapping}.
 *
 * <p>Pre-filtering in the query rather than filtering the result afterwards, for three reasons that
 * all bite in production: a page of 20 that is filtered afterwards returns fewer than 20 rows and a
 * wrong total, so paging breaks; the database still reads and ships rows the caller may not see; and
 * any aggregate (count, sum, exists) computed before the filter is simply wrong. Pushing the scope
 * into the {@code WHERE} clause makes all three correct by construction.
 */
public class DataScopePredicateFactory {

    /** Same idiom as db-core's {@code Predicates.allOf}, so the emitted SQL looks familiar. */
    private static final BooleanExpression ALWAYS_TRUE = Expressions.asBoolean(true).isTrue();
    private static final BooleanExpression NEVER = Expressions.asBoolean(true).isFalse();

    /**
     * The expression an unrestricted caller gets. Exposed because callers that short-circuit before
     * resolving a scope at all - an explicitly unscoped resource - still have to return something, and
     * it must be the same "no restriction" expression rather than {@code null}.
     */
    public BooleanExpression unrestricted() {
        return ALWAYS_TRUE;
    }

    /**
     * @return never {@code null}: an unrestricted scope compiles to an always-true expression rather
     *         than to {@code null}, so a caller that forgets to null-check cannot accidentally run the
     *         query unscoped. The optimizer discards the constant.
     */
    public <T> BooleanExpression toPredicate(DataScope scope, DataScopeMapping<T> mapping) {
        if (scope.isUnrestricted()) {
            return ALWAYS_TRUE;
        }
        if (scope.denies()) {
            return NEVER;
        }

        BooleanExpression disjunction = null;
        for (DataScope.Restriction restriction : scope.alternatives()) {
            BooleanExpression conjunction = toConjunction(restriction, mapping);
            if (conjunction == null) {
                continue;
            }
            disjunction = disjunction == null ? conjunction : disjunction.or(conjunction);
        }
        // Every alternative turned out unsatisfiable (e.g. every grant value failed to parse). That is
        // a denial, not an absence of restriction.
        return disjunction == null ? NEVER : disjunction;
    }

    /** @return {@code null} when this alternative can never match and should be dropped from the OR */
    private <T> BooleanExpression toConjunction(DataScope.Restriction restriction, DataScopeMapping<T> mapping) {
        BooleanExpression conjunction = null;
        for (Map.Entry<ScopeDimension, Set<String>> entry : restriction.values().entrySet()) {
            ScopeDimension dimension = entry.getKey();
            ScopeBinding<T> binding = mapping.binding(dimension)
                    .orElseThrow(() -> new SecurityConfigurationException(
                            "Resource '" + mapping.getResourceType() + "' is scoped by dimension '"
                                    + dimension + "' but its DataScopeMapping binds nothing for it. "
                                    + "Add the binding, or change the policy - an unbound dimension "
                                    + "cannot be enforced and must not be ignored. (DataScopePolicyValidator "
                                    + "catches this at startup for configured policies; reaching it here "
                                    + "means the dimension came from a custom DataScopeProvider.)"));

            BooleanExpression clause = binding.matches(entry.getValue());
            if (clause == null) {
                return null;
            }
            conjunction = conjunction == null ? clause : conjunction.and(clause);
        }
        return conjunction;
    }
}
