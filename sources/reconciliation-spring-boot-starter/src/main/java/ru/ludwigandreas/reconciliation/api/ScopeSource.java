package ru.ludwigandreas.reconciliation.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Answers "which local rows are scopes?" and "give me that one scope again".
 *
 * <p>The engine registers every scope it is shown and from then on decides for itself which are due,
 * because being due depends on per-scope walk state that only the engine holds. That is the
 * difference from {@link DemandProvider}, which is asked on every run for the records that need
 * attention and so has to answer a question it has no state for.
 *
 * @param <S>  the local scope type
 * @param <SK> the key that addresses a scope
 */
public interface ScopeSource<S, SK> {

    /**
     * One page of scopes, in ascending key order.
     *
     * <p>Keyset-paged so that discovery never loads every scope at once. The order must be stable and
     * must be the order of the keys themselves, because the last key of one page is the bound of the
     * next.
     *
     * @param after the last key of the previous page, or null to start at the beginning
     * @param limit the most scopes to return; fewer means there are no more
     * @return the scopes whose key is strictly greater than {@code after}
     */
    List<S> page(SK after, int limit);

    /**
     * Reloads one scope by key.
     *
     * @param key the scope key
     * @return the scope, or empty if its local row is gone - in which case the engine forgets the
     *         scope and its membership without reporting any member as having left
     */
    Optional<S> byKey(SK key);

    /**
     * The key of a scope.
     *
     * @param scope a scope
     * @return its key
     */
    SK keyOf(S scope);

    /**
     * A source of exactly one fixed scope that corresponds to no local row.
     *
     * <p>For the integration whose collection is not parametrised at all - one query, one feed.
     *
     * @param <S>   the scope type
     * @param <SK>  the key type
     * @param key   the key the single scope is stored under
     * @param scope the value handed to the feed and the reconciler
     * @return the source
     */
    static <S, SK> ScopeSource<S, SK> single(SK key, S scope) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(scope, "scope");
        return new ScopeSource<>() {
            @Override
            public List<S> page(SK after, int limit) {
                return after == null ? List.of(scope) : List.of();
            }

            @Override
            public Optional<S> byKey(SK requested) {
                return key.equals(requested) ? Optional.of(scope) : Optional.empty();
            }

            @Override
            public SK keyOf(S ignored) {
                return key;
            }
        };
    }
}
