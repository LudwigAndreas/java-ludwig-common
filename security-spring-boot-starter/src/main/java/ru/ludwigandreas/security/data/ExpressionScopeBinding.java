package ru.ludwigandreas.security.data;

import com.querydsl.core.types.dsl.BooleanExpression;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * You supply both halves yourself.
 *
 * <p>The escape hatch for rules no single path can express: a condition spanning two attributes of the
 * same child row, an {@code EXISTS} over an entity with no mapped association, a temporal window, a
 * recursive hierarchy. Everything downstream - union across roles, composition with other dimensions,
 * the audit trail, the fail-closed handling - works exactly as it does for the other two shapes.
 *
 * <pre>{@code
 * QOrderEntity order = QOrderEntity.orderEntity;
 * QOrderMentionEntity mention = QOrderMentionEntity.orderMentionEntity;
 *
 * .bindExpression(ScopeDimension.of("mentioned"),
 *         subjects -> JPAExpressions.selectOne()
 *                 .from(mention)
 *                 .where(mention.orderId.eq(order.id)
 *                         .and(mention.userId.in(subjects))
 *                         .and(mention.revokedAt.isNull()))
 *                 .exists(),
 *         (entity, subjects) -> entity.getMentions().stream()
 *                 .filter(m -> m.getRevokedAt() == null)
 *                 .anyMatch(m -> subjects.contains(m.getUserId())))
 * }</pre>
 *
 * <p><b>What you give up.</b> With the other two shapes the query predicate and the object check are
 * derived from one declaration, so they cannot disagree. Here they are two pieces of code that happen
 * to sit next to each other, and nothing in this module can tell you when they have drifted apart -
 * the failure mode being a list endpoint and a {@code findById} that answer differently for the same
 * caller and the same row. Test them against each other; the shipped
 * {@code ExpressionScopeBindingTest} shows the shape of such a test. Prefer
 * {@link PathScopeBinding} or {@link CollectionScopeBinding} whenever either can express the rule.
 *
 * <p><b>On exceptions.</b> If your predicate or your check throws, the exception propagates and the
 * request fails. That is deliberate: treating a bug as a denial would hide it behind a plausible 403,
 * and treating it as a grant is unthinkable. Keep both halves total.
 *
 * @param predicate   builds the query-time restriction from the allowed values; may return {@code null}
 *                    to mean "can never match", which is honoured as a denial
 * @param check       the same rule against an already-loaded object
 * @param description short human-readable label, used in configuration errors and audit records
 */
public record ExpressionScopeBinding<T>(
        Function<Set<String>, BooleanExpression> predicate,
        BiPredicate<T, Set<String>> check,
        String description) implements ScopeBinding<T> {

    public ExpressionScopeBinding {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(check, "check");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("a custom scope binding must carry a description - it is "
                    + "what a configuration error or an audit record has to name it by");
        }
    }

    @Override
    public BooleanExpression matches(Set<String> rawValues) {
        return rawValues == null || rawValues.isEmpty() ? null : predicate.apply(rawValues);
    }

    @Override
    public boolean matches(T target, Set<String> rawValues) {
        if (target == null || rawValues == null || rawValues.isEmpty()) {
            return false;
        }
        return check.test(target, rawValues);
    }

    @Override
    public String describe() {
        return "custom expression (" + description + ")";
    }
}
