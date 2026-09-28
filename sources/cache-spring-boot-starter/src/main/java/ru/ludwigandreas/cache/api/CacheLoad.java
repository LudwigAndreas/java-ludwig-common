package ru.ludwigandreas.cache.api;

import java.util.Objects;
import java.util.Optional;

/**
 * What a {@link CacheLoader} found: a value, a genuine absence, or a failure.
 *
 * <h2>Why three cases and not an {@code Optional}</h2>
 *
 * <p>This type exists so that negative caching can be safe, and it is the reason negative caching is
 * shaped into the API rather than added as a flag on it.
 *
 * <ul>
 *   <li>A <b>"not found"</b> answer is a fact about the data and may be cached, on its own shorter TTL,
 *       so that a hammering lookup for a key that does not exist stops reaching the store.</li>
 *   <li>An <b>error</b> - a timeout, a connection refused, a 5xx from a partner - must <b>never</b> be
 *       cached. Caching a failure turns a two-second blip into a sustained outage for exactly the keys
 *       that were unlucky, and it is self-reinforcing: the negative entry prevents the recovery probe
 *       that would have noticed the store came back.</li>
 * </ul>
 *
 * <p>A loader returning {@code Optional.empty()} for both makes that distinction unrepresentable, and
 * therefore makes the safe implementation impossible - the cache cannot tell which of the two it was
 * handed. So the loader says which, in its return type. A loader that throws is treated exactly as
 * {@link Failed}: an exception is already an unambiguous statement of failure, and requiring callers to
 * catch and re-wrap would be ceremony with no benefit.
 *
 * @param <V> the cached value type
 */
public sealed interface CacheLoad<V> {

    /** The value was found. */
    record Present<V>(V value) implements CacheLoad<V> {

        /** @throws NullPointerException if {@code value} is {@code null} - use {@link #absent()} */
        public Present {
            Objects.requireNonNull(value, "a present CacheLoad must carry a value; use CacheLoad.absent()");
        }
    }

    /**
     * The store answered, and the answer is that there is no such value.
     *
     * <p>Cacheable as a negative entry when the cache declares {@code negative.enabled}.
     */
    record Absent<V>() implements CacheLoad<V> {
    }

    /**
     * The store could not be asked, or could not answer.
     *
     * <p>Never cached. The cause is rethrown to the caller - a {@link RuntimeException} as itself, so
     * that an existing handler still recognises it, and anything else wrapped.
     */
    record Failed<V>(Throwable cause) implements CacheLoad<V> {

        /** @throws NullPointerException if {@code cause} is {@code null} */
        public Failed {
            Objects.requireNonNull(cause, "a failed CacheLoad must carry a cause");
        }
    }

    /**
     * The value was found.
     *
     * @param value the loaded value, never {@code null}
     * @param <V>   the cached value type
     * @return a present load
     */
    static <V> CacheLoad<V> present(V value) {
        return new Present<>(value);
    }

    /**
     * There is no such value, and the store said so.
     *
     * @param <V> the cached value type
     * @return an absent load, which may be cached as a negative entry
     */
    static <V> CacheLoad<V> absent() {
        return new Absent<>();
    }

    /**
     * The load could not be completed.
     *
     * @param cause what went wrong
     * @param <V>   the cached value type
     * @return a failed load, which is never cached
     */
    static <V> CacheLoad<V> failed(Throwable cause) {
        return new Failed<>(cause);
    }

    /**
     * Adapts a lookup that already returns an {@code Optional} and expresses failure by throwing.
     *
     * <p>The convenient form for the common case, where a repository returns {@code Optional} and any
     * problem arrives as an exception from the layer below. It is a named factory rather than an
     * overload of {@link CacheLoader} so that the ambiguity this type exists to remove cannot creep
     * back in through a shorter spelling: a reader of {@code CacheLoad.ofOptional(...)} can see that
     * empty was deliberately chosen to mean absent.
     *
     * @param value empty meaning <em>absent</em>, never meaning <em>failed</em>
     * @param <V>   the cached value type
     * @return {@link Present} or {@link Absent}
     */
    static <V> CacheLoad<V> ofOptional(Optional<V> value) {
        return value.map(CacheLoad::present).orElseGet(CacheLoad::absent);
    }
}
