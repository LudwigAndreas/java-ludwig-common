package ru.ludwigandreas.security.data;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.SimpleExpression;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import lombok.Getter;

/**
 * Declares how one resource is scoped: its logical name, its class, and how each {@link ScopeDimension}
 * is enforced against it.
 *
 * <p>Register one bean per scoped resource and the rest of the module works without further wiring:
 *
 * <pre>{@code
 * @Bean
 * DataScopeMapping<OrderEntity> orderScopeMapping() {
 *     QOrderEntity order = QOrderEntity.orderEntity;
 *     QOrderMentionEntity mention = QOrderMentionEntity.orderMentionEntity;
 *     return DataScopeMapping.forResource("order", OrderEntity.class)
 *             // one column, one value
 *             .owner(order.createdBy, OrderEntity::getCreatedBy)
 *             .tenant(order.tenantId, OrderEntity::getTenantId, UUID::fromString)
 *             // one row, many related values
 *             .bindCollection(ScopeDimension.of("mentioned"),
 *                     order.mentions.any().userId,
 *                     e -> e.getMentions().stream().map(OrderMention::getUserId).toList())
 *             // anything else
 *             .bindExpression(ScopeDimension.of("delegated"), subjects -> ..., (entity, subjects) -> ...)
 *             .build();
 * }
 * }</pre>
 *
 * <p>A policy may only use dimensions the mapping declares, and that is checked at startup
 * ({@code DataScopePolicyValidator}) rather than on the first request that happens to exercise the
 * combination. "Only their own orders" that quietly degrades to "all orders" because the owner column
 * was never mapped is the exact bug this mechanism exists to prevent, and finding it at deploy time
 * rather than in production is most of the value.
 */
@Getter
public final class DataScopeMapping<T> {

    private final String resourceType;
    private final Class<T> resourceClass;
    private final Map<ScopeDimension, ScopeBinding<T>> bindings;

    private DataScopeMapping(String resourceType, Class<T> resourceClass,
                             Map<ScopeDimension, ScopeBinding<T>> bindings) {
        this.resourceType = resourceType;
        this.resourceClass = resourceClass;
        this.bindings = Map.copyOf(bindings);
    }

    public static <T> Builder<T> forResource(String resourceType, Class<T> resourceClass) {
        return new Builder<>(resourceType, resourceClass);
    }

    public Optional<ScopeBinding<T>> binding(ScopeDimension dimension) {
        return Optional.ofNullable(bindings.get(dimension));
    }

    public boolean supports(ScopeDimension dimension) {
        return bindings.containsKey(dimension);
    }

    /** Every dimension this resource can be scoped along - what startup validation checks a policy against. */
    public Set<ScopeDimension> dimensions() {
        return bindings.keySet();
    }

    public static final class Builder<T> {

        private final String resourceType;
        private final Class<T> resourceClass;
        private final Map<ScopeDimension, ScopeBinding<T>> bindings = new LinkedHashMap<>();

        private Builder(String resourceType, Class<T> resourceClass) {
            this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
            this.resourceClass = Objects.requireNonNull(resourceClass, "resourceClass");
        }

        // --- single-valued columns ------------------------------------------------------------------

        /** The creator column - with db-core's audited entities this is {@code createdBy}. */
        public Builder<T> owner(SimpleExpression<String> path, Function<T, String> extractor) {
            return bind(ScopeDimension.OWNER, path, extractor);
        }

        public <V> Builder<T> owner(SimpleExpression<V> path, Function<T, V> extractor,
                                    Function<String, V> parser) {
            return bind(ScopeDimension.OWNER, path, extractor, parser);
        }

        public Builder<T> tenant(SimpleExpression<String> path, Function<T, String> extractor) {
            return bind(ScopeDimension.TENANT, path, extractor);
        }

        public <V> Builder<T> tenant(SimpleExpression<V> path, Function<T, V> extractor,
                                     Function<String, V> parser) {
            return bind(ScopeDimension.TENANT, path, extractor, parser);
        }

        public Builder<T> partner(SimpleExpression<String> path, Function<T, String> extractor) {
            return bind(ScopeDimension.PARTNER, path, extractor);
        }

        public <V> Builder<T> partner(SimpleExpression<V> path, Function<T, V> extractor,
                                      Function<String, V> parser) {
            return bind(ScopeDimension.PARTNER, path, extractor, parser);
        }

        /** Any further axis a {@link DataScopeProvider} produces (region, branch, contract). */
        public Builder<T> bind(ScopeDimension dimension, SimpleExpression<String> path,
                               Function<T, String> extractor) {
            return bind(dimension, path, extractor, Function.identity());
        }

        public <V> Builder<T> bind(ScopeDimension dimension, SimpleExpression<V> path,
                                   Function<T, V> extractor, Function<String, V> parser) {
            return register(dimension, new PathScopeBinding<>(path, extractor, parser));
        }

        // --- many-valued associations ---------------------------------------------------------------

        /**
         * The row relates to several values and matches when any one of them is allowed - "orders this
         * user is mentioned on". See {@link CollectionScopeBinding} for what the path has to look like.
         */
        public Builder<T> bindCollection(ScopeDimension dimension, SimpleExpression<String> path,
                                         Function<T, Collection<String>> extractor) {
            return bindCollection(dimension, path, extractor, Function.identity());
        }

        public <V> Builder<T> bindCollection(ScopeDimension dimension, SimpleExpression<V> path,
                                             Function<T, Collection<V>> extractor,
                                             Function<String, V> parser) {
            return register(dimension, new CollectionScopeBinding<>(path, extractor, parser));
        }

        // --- fully custom ---------------------------------------------------------------------------

        /**
         * Both halves supplied by the caller, for rules no single path can express. Powerful, and the
         * one shape where nothing checks that the query predicate and the object check agree - see
         * {@link ExpressionScopeBinding}.
         */
        public Builder<T> bindExpression(ScopeDimension dimension,
                                         Function<Set<String>, BooleanExpression> predicate,
                                         BiPredicate<T, Set<String>> check) {
            return bindExpression(dimension, predicate, check, dimension.name());
        }

        public Builder<T> bindExpression(ScopeDimension dimension,
                                         Function<Set<String>, BooleanExpression> predicate,
                                         BiPredicate<T, Set<String>> check,
                                         String description) {
            return register(dimension, new ExpressionScopeBinding<>(predicate, check, description));
        }

        private Builder<T> register(ScopeDimension dimension, ScopeBinding<T> binding) {
            Objects.requireNonNull(dimension, "dimension");
            ScopeBinding<T> clash = bindings.put(dimension, binding);
            if (clash != null) {
                // Silently keeping the last one would make the enforced rule depend on the order the
                // builder happened to be called in.
                throw new IllegalStateException("Dimension '" + dimension + "' is bound twice on resource '"
                        + resourceType + "': " + clash.describe() + " and " + binding.describe());
            }
            return this;
        }

        public DataScopeMapping<T> build() {
            if (bindings.isEmpty()) {
                throw new IllegalStateException("DataScopeMapping for '" + resourceType
                        + "' declares no dimension; a mapping that scopes nothing would make every "
                        + "restricted policy on this resource silently unenforceable");
            }
            return new DataScopeMapping<>(resourceType, resourceClass, bindings);
        }
    }
}
