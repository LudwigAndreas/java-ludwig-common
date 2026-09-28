package ru.ludwigandreas.cache.shared;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import ru.ludwigandreas.cache.api.CacheSettings;
import ru.ludwigandreas.cache.error.CacheConfigurationException;
import ru.ludwigandreas.cache.metrics.CacheMetrics;

/**
 * One cache's Redis keyspace.
 *
 * <h2>The key shape</h2>
 *
 * <p>{@code ludwig:{app}:{namespace}:v{version}:{key}}, plus two reserved siblings: {@code :__shape},
 * which records the value type's fingerprint so a stale version fails loudly (see {@link ValueShape}), and
 * {@code :__lease:{key}}, the cluster-wide load lease. The application segment is not decoration: two
 * services sharing a Redis with the same cache name would otherwise share a keyspace with two value types
 * in it, and the startup validator refuses a shared tier with no {@code spring.application.name}.
 *
 * <h2>Why the entry carries its own write time</h2>
 *
 * <p>Redis expresses one expiry per key, and this module needs two: the entry is <em>fresh</em> for the
 * TTL and <em>present</em> for the TTL plus the stale grace. The Redis expiry is set to the longer of the
 * two and freshness is computed from the stored write time, which is what makes jittered early refresh and
 * lease-loser stale reads possible at all. For a {@link ru.ludwigandreas.cache.api.CachePurpose#SECURITY}
 * cache the grace is zero, so the two coincide.
 */
@Slf4j
public class RedisSharedCacheView<V> implements SharedCacheView<V> {

    private static final String SHAPE_SUFFIX = ":__shape";

    private static final String LEASE_SUFFIX = ":__lease:";

    private static final String INVALIDATION_SUFFIX = ":__invalidate";

    private static final String FIELD_VALUE = "value";

    private static final String FIELD_WRITTEN_AT = "writtenAt";

    private static final String FIELD_NEGATIVE = "negative";

    private static final String FIELD_SHAPE = "shape";

    /** How long an unreachable tier is left alone before the next attempt re-tests it. */
    private static final Duration UNAVAILABLE_COOLDOWN = Duration.ofSeconds(10);

    /** How many keys a namespace sweep asks for per SCAN round trip. */
    private static final int SCAN_BATCH = 500;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RedisMessageListenerContainer listeners;
    private final CacheMetrics metrics;
    private final Clock clock;
    private final CacheSettings settings;
    private final Class<V> valueType;

    private final String keyPrefix;
    private final String shape;
    private final String instanceId = UUID.randomUUID().toString();

    private volatile boolean verified;
    private volatile boolean unusable;
    private volatile long unavailableUntilMillis;

    RedisSharedCacheView(StringRedisTemplate redis,
                         ObjectMapper objectMapper,
                         RedisMessageListenerContainer listeners,
                         CacheMetrics metrics,
                         Clock clock,
                         String applicationName,
                         CacheSettings settings,
                         Class<V> valueType) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.listeners = listeners;
        this.metrics = metrics;
        this.clock = clock;
        this.settings = settings;
        this.valueType = valueType;
        this.keyPrefix = "ludwig:" + applicationName + ":" + settings.keyNamespace().name()
                + ":v" + settings.keyNamespace().version();
        this.shape = ValueShape.of(valueType);
    }

    /**
     * Records this process's value shape under the namespace version, or fails the context.
     *
     * <p>Called once, when the cache is built. Three outcomes:
     *
     * <ul>
     *   <li><b>nothing recorded yet</b> - this process records it and the tier is verified;</li>
     *   <li><b>the same fingerprint recorded</b> - verified;</li>
     *   <li><b>a different fingerprint recorded</b> - startup fails. The version was reused across a
     *       value-shape change, which is the silent-deserialization hazard the version exists to
     *       prevent.</li>
     * </ul>
     *
     * <p>An unreachable Redis is the fourth outcome and is <em>not</em> a startup failure: the tier stays
     * unverified, which means it neither serves nor stores, the cache runs local-only, and the check is
     * retried on use. Failing the pod because a performance arrangement is briefly unavailable would be
     * the wrong trade; serving from an unverified namespace would be the hazard itself.
     *
     * @throws CacheConfigurationException if the recorded fingerprint differs from this process's
     */
    public void verifyValueShape() {
        String recorded;
        try {
            recorded = recordOrReadShape();
        } catch (RuntimeException e) {
            verified = false;
            log.warn("Cache '{}' could not verify its shared key namespace at startup ({}); it runs"
                            + " local-only and neither serves nor stores shared entries until the check"
                            + " succeeds, because serving from an unverified namespace is the hazard the"
                            + " version exists to prevent", settings.name(), e.toString());
            return;
        }
        if (recorded == null || recorded.equals(shape)) {
            verified = true;
            return;
        }
        throw new CacheConfigurationException(
                "Cache '" + settings.name() + "' declares key namespace version "
                        + settings.keyNamespace().version() + ", but entries already in Redis under that"
                        + " version were written from a differently-shaped " + valueType.getName()
                        + " (recorded shape " + recorded + ", this process " + shape + "). A pod on the"
                        + " other release reading this one's entries would deserialize into something"
                        + " subtly wrong rather than fail. Raise ludwig.cache.caches." + settings.name()
                        + ".key-namespace.version.");
    }

    /**
     * Records this process's shape if the namespace is new, and returns whatever is now recorded.
     *
     * @return the recorded fingerprint, or {@code null} if the namespace holds none
     */
    private String recordOrReadShape() {
        Boolean written = redis.opsForValue().setIfAbsent(keyPrefix + SHAPE_SUFFIX, shape);
        if (Boolean.TRUE.equals(written)) {
            return shape;
        }
        return redis.opsForValue().get(keyPrefix + SHAPE_SUFFIX);
    }

    @Override
    public boolean isAvailable() {
        if (unusable) {
            return false;
        }
        if (!verified && cooledDown()) {
            reverifyQuietly();
        }
        return verified && cooledDown();
    }

    @Override
    public Optional<SharedEntry<V>> read(String renderedKey) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try {
            String payload = redis.opsForValue().get(entryKey(renderedKey));
            if (payload == null) {
                return Optional.empty();
            }
            return decode(payload);
        } catch (RuntimeException e) {
            markUnavailable("read", e);
            return Optional.empty();
        }
    }

    @Override
    public void write(String renderedKey, SharedEntry<V> entry) {
        if (!isAvailable()) {
            return;
        }
        try {
            redis.opsForValue().set(entryKey(renderedKey), encode(entry), settings.physicalTtl());
        } catch (RuntimeException e) {
            markUnavailable("write", e);
        }
    }

    /**
     * Deletes one entry, retrying without a backoff.
     *
     * <p>No sleep between attempts, deliberately: this runs in {@code afterCommit} on the request thread,
     * and a backoff would hold a response open to wait for a Redis that is probably not coming back within
     * it. The retries cover a connection that was reaped between the commit and the delete, which is the
     * common case and is fixed by trying again immediately. A genuinely down Redis is reported rather than
     * waited for.
     */
    @Override
    public boolean delete(String renderedKey) {
        if (unusable || !verified) {
            return true;
        }
        RuntimeException last = null;
        for (int attempt = 0; attempt <= settings.shared().evictionRetries(); attempt++) {
            try {
                redis.delete(entryKey(renderedKey));
                return true;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        markUnavailable("evict", last);
        return false;
    }

    @Override
    public boolean deleteAll() {
        if (unusable || !verified) {
            return true;
        }
        ScanOptions options = ScanOptions.scanOptions().match(keyPrefix + ":*").count(SCAN_BATCH).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            List<String> batch = new ArrayList<>();
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (!key.endsWith(SHAPE_SUFFIX)) {
                    batch.add(key);
                }
                if (batch.size() >= SCAN_BATCH) {
                    redis.delete(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                redis.delete(batch);
            }
            return true;
        } catch (RuntimeException e) {
            markUnavailable("evict-all", e);
            return false;
        }
    }

    @Override
    public boolean tryAcquireLoadLease(String renderedKey) {
        if (!isAvailable()) {
            return true;
        }
        try {
            Boolean taken = redis.opsForValue().setIfAbsent(
                    keyPrefix + LEASE_SUFFIX + renderedKey, instanceId, settings.stampede().leaseTtl());
            return Boolean.TRUE.equals(taken);
        } catch (RuntimeException e) {
            markUnavailable("lease", e);
            return true;
        }
    }

    @Override
    public void publishInvalidation(String renderedKey) {
        if (!settings.shared().invalidationEnabled() || !isAvailable()) {
            return;
        }
        try {
            redis.convertAndSend(keyPrefix + INVALIDATION_SUFFIX, instanceId + "|" + renderedKey);
        } catch (RuntimeException e) {
            markUnavailable("invalidate", e);
        }
    }

    @Override
    public void subscribeInvalidations(Consumer<String> listener) {
        if (!settings.shared().invalidationEnabled()) {
            return;
        }
        listeners.addMessageListener((message, pattern) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            int separator = body.indexOf('|');
            if (separator < 0) {
                return;
            }
            if (instanceId.equals(body.substring(0, separator))) {
                // Our own announcement: this replica evicted locally before it published.
                return;
            }
            listener.accept(body.substring(separator + 1));
        }, new ChannelTopic(keyPrefix + INVALIDATION_SUFFIX));
    }

    private String entryKey(String renderedKey) {
        return keyPrefix + ":" + renderedKey;
    }

    private String encode(SharedEntry<V> entry) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.set(FIELD_VALUE, entry.negative() || entry.value() == null
                ? objectMapper.nullNode()
                : objectMapper.valueToTree(entry.value()));
        envelope.put(FIELD_WRITTEN_AT, entry.writtenAtMillis());
        envelope.put(FIELD_NEGATIVE, entry.negative());
        envelope.put(FIELD_SHAPE, shape);
        return envelope.toString();
    }

    /**
     * Decodes an envelope, discarding one whose value shape is not this process's.
     *
     * <p>Defence in depth behind the namespace version: the version is what <em>should</em> keep the two
     * releases apart, and this is what happens when it did not. Counted as a shape mismatch and treated as
     * a miss, which costs a load; the alternative is deserializing a document whose fields have moved.
     */
    private Optional<SharedEntry<V>> decode(String payload) {
        try {
            JsonNode envelope = objectMapper.readTree(payload);
            if (!shape.equals(envelope.path(FIELD_SHAPE).asText())) {
                metrics.recordSharedShapeMismatch(settings.name());
                return Optional.empty();
            }
            boolean negative = envelope.path(FIELD_NEGATIVE).asBoolean();
            long writtenAt = envelope.path(FIELD_WRITTEN_AT).asLong();
            JsonNode value = envelope.path(FIELD_VALUE);
            V decoded = negative || value.isNull() || value.isMissingNode()
                    ? null
                    : objectMapper.treeToValue(value, valueType);
            return Optional.of(new SharedEntry<>(decoded, writtenAt, negative));
        } catch (RuntimeException | JsonProcessingException e) {
            metrics.recordSharedShapeMismatch(settings.name());
            log.warn("Cache '{}' discarded an unreadable shared entry: {}", settings.name(), e.toString());
            return Optional.empty();
        }
    }

    private boolean cooledDown() {
        return clock.millis() >= unavailableUntilMillis;
    }

    private void markUnavailable(String operation, RuntimeException cause) {
        unavailableUntilMillis = clock.millis() + UNAVAILABLE_COOLDOWN.toMillis();
        metrics.recordSharedError(settings.name(), operation);
        metrics.recordSharedTierAvailability(settings.name(), false);
        if ("evict".equals(operation)) {
            // The counter is the caller's: DefaultLudwigCache records it from delete()'s return value, so
            // that a failed shared eviction is counted the same way whatever the tier is made of.
            log.error("Cache '{}' could not delete a shared entry after {} attempts. Every replica now"
                            + " serves the stale value until the TTL expires - this is an incident, not a"
                            + " retryable blip", settings.name(),
                    settings.shared().evictionRetries() + 1, cause);
            return;
        }
        log.warn("Cache '{}' shared-tier {} failed; falling back to the local tier for {}",
                settings.name(), operation, UNAVAILABLE_COOLDOWN, cause);
    }

    private void reverifyQuietly() {
        String recorded;
        try {
            recorded = recordOrReadShape();
        } catch (RuntimeException e) {
            unavailableUntilMillis = clock.millis() + UNAVAILABLE_COOLDOWN.toMillis();
            return;
        }
        if (recorded == null || recorded.equals(shape)) {
            verified = true;
            metrics.recordSharedTierAvailability(settings.name(), true);
            log.info("Cache '{}' verified its shared key namespace; the shared tier is now in use",
                    settings.name());
            return;
        }
        unusable = true;
        log.error("Cache '{}' found key namespace version {} already recorded against a differently"
                        + "-shaped {} (recorded {}, this process {}). The shared tier is disabled for the"
                        + " life of this process rather than used; raise"
                        + " ludwig.cache.caches.{}.key-namespace.version and redeploy",
                settings.name(), settings.keyNamespace().version(), valueType.getName(), recorded,
                shape, settings.name());
    }
}
