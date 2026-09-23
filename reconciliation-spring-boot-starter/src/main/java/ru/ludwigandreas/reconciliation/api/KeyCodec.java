package ru.ludwigandreas.reconciliation.api;

import java.util.UUID;
import java.util.function.Function;

/**
 * Converts a task's correlation key to and from the {@code correlation_key} text column.
 *
 * <h2>Why this exists rather than {@code toString()}</h2>
 *
 * <p>Staging is the whole point of this module: a fetched record is written to a row and applied
 * later, possibly by a different instance, possibly after a restart. That means the key has to
 * survive a round trip through a database column, and {@code toString()} only does half of it. A
 * module that could write a key but not read it back would be unable to resume after the restart it
 * exists to survive.
 *
 * @param <K> the task's correlation key type
 */
public interface KeyCodec<K> {

    /**
     * Renders a key for storage.
     *
     * @param key the key
     * @return its text form, which must be stable across processes and JVM versions
     */
    String encode(K key);

    /**
     * Parses a stored key.
     *
     * @param encoded the text form written by {@link #encode}
     * @return the key
     * @throws IllegalArgumentException if the stored text is not a valid key for this task
     */
    K decode(String encoded);

    /** Codec for tasks whose correlation key is already a string. */
    static KeyCodec<String> ofString() {
        return of(Function.identity(), Function.identity());
    }

    /** Codec for {@link UUID} keys. */
    static KeyCodec<UUID> ofUuid() {
        return of(UUID::toString, UUID::fromString);
    }

    /** Codec for {@link Long} keys. */
    static KeyCodec<Long> ofLong() {
        return of(String::valueOf, Long::valueOf);
    }

    /**
     * Builds a codec from a pair of functions.
     *
     * @param <K>    the key type
     * @param encode renders a key
     * @param decode parses a key
     * @return the codec
     */
    static <K> KeyCodec<K> of(Function<K, String> encode, Function<String, K> decode) {
        return new KeyCodec<>() {
            @Override
            public String encode(K key) {
                return encode.apply(key);
            }

            @Override
            public K decode(String stored) {
                return decode.apply(stored);
            }
        };
    }
}
