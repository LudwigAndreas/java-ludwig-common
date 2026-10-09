package ru.ludwigandreas.odatafilter.querydsl;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.PathBuilder;
import org.springframework.data.domain.Sort;

/**
 * The one place a caller-supplied property path becomes a QueryDSL path.
 *
 * <h2>Why this is public, and why a repository must not do it itself</h2>
 *
 * <p>{@code $orderby} names its properties as strings - that is what the option is - so the ordering
 * expressions cannot come from a generated Q-type the way a fixed predicate does. They have to be
 * built at runtime with a {@link PathBuilder}, which is the one construct in this platform's data
 * access the compiler does not check.
 *
 * <p>Before this class existed, two services each carried a private copy of the walk below, together
 * with a {@code static final PathBuilder ROOT} field and a
 * {@code @SuppressWarnings({"rawtypes","unchecked"})}. They were identical, down to the comment. The
 * reason that duplication was dangerous rather than merely untidy is {@link #alias(Class)}: the
 * ordering expressions and the predicate must address the <em>same</em> alias, and when they do not,
 * JPQL does not fail - it cross-joins the table to itself and returns a result that is wrong in a way
 * no test over a small fixture notices. A correctness condition invisible at each of two call sites
 * is one that will eventually be broken at one of them.
 *
 * @see ru.ludwigandreas.odatafilter.execution.ODataQueryExecutor for the usual case, where a consumer
 *     needs none of this directly
 */
public final class ODataPaths {

    private ODataPaths() {
    }

    /**
     * The query alias this module builds every path against: the uncapitalized simple class name.
     *
     * <p>That is Spring Data's {@code SimpleEntityPathResolver} convention, which is also what
     * QueryDSL's generated default instance uses - {@code QProductEntity.productEntity} is aliased
     * {@code productEntity}. So a repository passing its generated default instance to the executor
     * already agrees with this, and one passing {@code new QProductEntity("p")} does not. The executor
     * checks rather than trusting.
     */
    public static String alias(Class<?> entityType) {
        String simpleName = entityType.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }

    /** A dynamic root for {@code entityType}, aliased per {@link #alias(Class)}. */
    public static PathBuilder<?> root(Class<?> entityType) {
        return new PathBuilder<>(entityType, alias(entityType));
    }

    /**
     * Converts a resolved {@link Sort} - the caller's {@code $orderby} with the entity's
     * {@code defaultOrderBy} tie-breaker already appended - into QueryDSL ordering expressions over
     * {@code entityType}'s root.
     *
     * <p>The {@code Sort} comes from {@code ODataQuery.pageable().getSort()}, so every path in it has
     * already been checked against the entity's {@code @Filterable(sortable = true)} declarations.
     * Nothing here re-validates, and nothing here accepts a path from anywhere else.
     *
     * @return the ordering expressions, in the sort's own order; empty if the sort is unsorted
     */
    public static OrderSpecifier<?>[] orderSpecifiers(Class<?> entityType, Sort sort) {
        PathBuilder<?> root = root(entityType);
        return sort.stream()
                .map(order -> orderSpecifier(root, order.getProperty(), order.isAscending()))
                .toArray(OrderSpecifier<?>[]::new);
    }

    /**
     * Walks a {@code .}- or {@code /}-separated property path to a comparable expression.
     *
     * <p>Both separators are accepted because the same path is spelled two ways in this platform: OData
     * uses {@code /} and Spring Data's {@code Sort} uses {@code .}, and the conversion between them has
     * already bitten once by being done in one direction only.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OrderSpecifier<?> orderSpecifier(PathBuilder<?> root, String propertyPath, boolean ascending) {
        String[] segments = propertyPath.split("[./]");
        PathBuilder<?> parent = root;
        for (int i = 0; i < segments.length - 1; i++) {
            parent = parent.get(segments[i]);
        }
        ComparableExpressionBase path = parent.getComparable(segments[segments.length - 1], Comparable.class);
        return new OrderSpecifier(ascending ? Order.ASC : Order.DESC, path);
    }

    /**
     * Fails unless {@code root} addresses the alias this module builds predicates against.
     *
     * <p><b>This runtime check is deliberate, and it is the enforcement half of a rule no static
     * analysis can express.</b> Checkstyle cannot see it because the alias is not source text at the
     * point it matters - it is whichever string a {@code Q}-type instance was constructed with, often
     * in another class. ArchUnit cannot see it because it is the <em>value</em> of a field, and
     * bytecode analysis reads types and references, not values. So the rule is encoded here, where the
     * two aliases are both in hand, rather than written down in a README and broken silently.
     *
     * @param entityType the entity whose policy and predicate are in play
     * @param root       the root the consumer intends to query
     * @throws IllegalArgumentException if the aliases differ, naming both
     */
    public static void requireMatchingAlias(Class<?> entityType, Path<?> root) {
        String expected = alias(entityType);
        String actual = root.getMetadata().getName();
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    ("the query root for %s is aliased '%s', but this module builds its predicate and its "
                            + "ordering against '%s'. Two different aliases do not fail in JPQL - they "
                            + "cross-join %s to itself and return rows that are silently wrong. Pass the "
                            + "generated default instance (aliased '%s'), not a root constructed with a "
                            + "name of its own.")
                            .formatted(entityType.getSimpleName(), actual, expected,
                                    entityType.getSimpleName(), expected));
        }
    }
}
