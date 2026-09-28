package ru.ludwigandreas.security.data;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.SimpleExpression;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * One column holds one value, and the row matches when that value is among the allowed ones.
 *
 * <p>The default shape, and the one to reach for unless the rule genuinely needs something else: both
 * halves are compile-time bound - a generated Q-type path and a method reference on the same field - so
 * renaming the field breaks the build instead of silently un-scoping the resource.
 *
 * @param path      the generated QueryDSL path, e.g. {@code QOrderEntity.orderEntity.createdBy}
 * @param extractor reads the same value off an instance, e.g. {@code OrderEntity::getCreatedBy}
 * @param parser    converts a scope value into the column's type, e.g. {@code UUID::fromString}
 */
public record PathScopeBinding<T, V>(
        SimpleExpression<V> path,
        Function<T, V> extractor,
        Function<String, V> parser) implements ScopeBinding<T> {

    public PathScopeBinding {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(extractor, "extractor");
        Objects.requireNonNull(parser, "parser");
    }

    @Override
    public BooleanExpression matches(Set<String> rawValues) {
        List<V> parsed = ScopeValues.parse(rawValues, parser, path);
        return parsed.isEmpty() ? null : path.in(parsed);
    }

    @Override
    public boolean matches(T target, Set<String> rawValues) {
        if (target == null) {
            return false;
        }
        V actual = extractor.apply(target);
        return actual != null && ScopeValues.parse(rawValues, parser, path).contains(actual);
    }

    @Override
    public String describe() {
        return "path " + path;
    }
}
