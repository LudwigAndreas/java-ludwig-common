package ru.ludwigandreas.security.data;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.SimpleExpression;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * The row relates to <em>several</em> values and matches when any one of them is allowed.
 *
 * <p>This is the shape for "orders this user is mentioned on", "cases assigned to any of my teams",
 * "documents shared with me" - membership rather than ownership. It exists because the single-valued
 * {@link PathScopeBinding} cannot express it on the load-time side: an extractor returning one value
 * has no way to say "this order mentions four people and you are one of them", and the fail-closed
 * default would refuse a caller who is legitimately on the row.
 *
 * <pre>{@code
 * QOrderEntity order = QOrderEntity.orderEntity;
 * DataScopeMapping.forResource("order", OrderEntity.class)
 *         .bindCollection(ScopeDimension.of("mentioned"),
 *                 order.mentions.any().userId,                       // query side
 *                 e -> e.getMentions().stream().map(Mention::getUserId).toList())   // load side
 *         .build();
 * }</pre>
 *
 * <p>On the query side the path is expected to traverse a to-many association - QueryDSL's
 * {@code any()} - which JPA renders as a correlated {@code EXISTS} subquery. That keeps paging and
 * counts correct, which a join would not: a join multiplies rows by the number of matching children
 * and silently inflates both the page and its total.
 *
 * <p>One QueryDSL constraint worth knowing: each {@code any()} is an independent subquery, so this
 * shape cannot express a condition spanning two attributes of the <em>same</em> child row
 * ("mentioned <em>and</em> mentioned with role X"). That needs {@link ExpressionScopeBinding}.
 *
 * @param path      a path through the association, e.g. {@code order.mentions.any().userId}
 * @param extractor every value the object relates to; an empty or {@code null} result means no match
 * @param parser    converts a scope value into the path's type
 */
public record CollectionScopeBinding<T, V>(
        SimpleExpression<V> path,
        Function<T, Collection<V>> extractor,
        Function<String, V> parser) implements ScopeBinding<T> {

    public CollectionScopeBinding {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(extractor, "extractor");
        Objects.requireNonNull(parser, "parser");
    }

    @Override
    public BooleanExpression matches(Set<String> rawValues) {
        List<V> parsed = ScopeValues.parse(rawValues, parser, path);
        return parsed.isEmpty() ? null : path.in(parsed);
    }

    /**
     * Intersection, not equality: the object matches when any value it relates to is allowed. Iterating
     * the (normally short) allowed list against a {@code Collection#contains} keeps this cheap without
     * assuming the extractor returned a set.
     */
    @Override
    public boolean matches(T target, Set<String> rawValues) {
        if (target == null) {
            return false;
        }
        Collection<V> actual = extractor.apply(target);
        if (actual == null || actual.isEmpty()) {
            return false;
        }
        return ScopeValues.parse(rawValues, parser, path).stream().anyMatch(actual::contains);
    }

    @Override
    public String describe() {
        return "collection path " + path;
    }
}
