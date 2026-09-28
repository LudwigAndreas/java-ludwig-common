package ru.ludwigandreas.cache.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.CacheLoad;
import ru.ludwigandreas.cache.api.CachePurpose;
import ru.ludwigandreas.cache.api.CacheTier;
import ru.ludwigandreas.cache.config.CacheProperties;
import ru.ludwigandreas.cache.error.CacheConfigurationException;
import ru.ludwigandreas.cache.metrics.NoopCacheMetrics;
import ru.ludwigandreas.cache.shared.RedisSharedCacheTier;
import ru.ludwigandreas.cache.shared.SharedCacheTier;
import ru.ludwigandreas.cache.test.LudwigCacheTestSupport;
import ru.ludwigandreas.testsupport.container.Containers;

/**
 * The shared tier against a real Redis.
 *
 * <p>Two independent registries over one Redis stand in for two replicas, which is what makes the behaviours
 * worth testing here testable at all: promotion of another replica's entry, the invalidation announcement that
 * drops a local copy on a replica that did not perform the eviction, and the key-namespace version that keeps
 * two releases apart during a rolling deploy.
 *
 * <p>Only this class needs a container. Every local-tier test in this module runs without Docker, which is
 * deliberate - a caching module whose whole suite needed Docker is a caching module people stop running tests
 * for.
 */
class SharedTierIntegrationTest {

    private static final String CACHE = "partners";

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(5);

    private static LettuceConnectionFactory connectionFactory;

    private static RedisMessageListenerContainer listeners;

    @BeforeAll
    static void startRedis() {
        GenericContainer<?> redis = Containers.redis();
        connectionFactory = new LettuceConnectionFactory(
                redis.getHost(), redis.getMappedPort(Containers.REDIS_PORT));
        connectionFactory.afterPropertiesSet();
        listeners = new RedisMessageListenerContainer();
        listeners.setConnectionFactory(connectionFactory);
        listeners.afterPropertiesSet();
        listeners.start();
    }

    @AfterAll
    static void stopRedis() throws Exception {
        listeners.destroy();
        connectionFactory.destroy();
    }

    @BeforeEach
    void emptyRedis() {
        // One shared container means a test cannot assume an empty keyspace by construction; see Containers.
        new StringRedisTemplate(connectionFactory).execute(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    connection.serverCommands().flushAll();
                    return null;
                });
    }

    @Test
    @DisplayName("one replica's entry is served to another, and promoted into its local tier")
    void anEntryWrittenByOneReplicaIsServedToAnother() {
        Replica first = new Replica(1);
        Replica second = new Replica(1);

        first.cache().put(new TestPartner("acme"), new PartnerRecord("Acme", 3));

        assertThat(second.cache().get(new TestPartner("acme")))
                .as("read across replicas without touching the store")
                .contains(new PartnerRecord("Acme", 3));
    }

    @Test
    @DisplayName("a cold key costs one load per replica, not one per request")
    void aSharedHitSavesTheSecondReplicaTheLoad() {
        Replica first = new Replica(1);
        Replica second = new Replica(1);

        first.cache().get(new TestPartner("acme"), key -> CacheLoad.present(new PartnerRecord("Acme", 3)));
        boolean[] loaded = {false};
        second.cache().get(new TestPartner("acme"), key -> {
            loaded[0] = true;
            return CacheLoad.present(new PartnerRecord("Acme", 3));
        });

        assertThat(loaded[0]).isFalse();
    }

    /**
     * The third part of a two-tier eviction, which is the one that is easy to forget.
     *
     * <p>Deleting the shared entry does nothing about the local copies every other replica holds. Without the
     * announcement, local TTL is the only bound on them - so a successful eviction still leaves a stale read on
     * every replica but the one that performed it.
     */
    @Test
    @DisplayName("an eviction on one replica drops the other replica's local copy too")
    void evictionReachesTheOtherReplicasLocalCopy() {
        Replica first = new Replica(1);
        Replica second = new Replica(1);
        first.cache().put(new TestPartner("acme"), new PartnerRecord("Acme", 3));
        assertThat(second.cache().get(new TestPartner("acme"))).isPresent();
        assertThat(second.cache().estimatedSize()).as("the second replica promoted it locally").isEqualTo(1);

        first.cache().evict(new TestPartner("acme"));

        await(() -> second.cache().estimatedSize() == 0);
    }

    /**
     * The rolling-deploy property, tested directly.
     *
     * <p>v1 and v2 pods share one Redis. If v2 changes the serialized shape of a cached value and the key
     * namespace version was not bumped, a v1 pod reads a v2 entry and either throws or - worse - deserializes
     * into something subtly wrong. Bumping the version makes the two releases invisible to each other, which is
     * a cold cache rather than a corrupt one.
     */
    @Test
    @DisplayName("entries under one key namespace version are invisible to a cache configured at the next")
    void keyNamespaceVersionsAreIsolated() {
        Replica v1 = new Replica(1);
        Replica v2 = new Replica(2);

        v1.cache().put(new TestPartner("acme"), new PartnerRecord("Acme", 3));

        assertThat(v2.cache().get(new TestPartner("acme"))).isEmpty();
        assertThat(v1.cache().get(new TestPartner("acme"))).isPresent();
    }

    /**
     * Forgetting to bump the version is made loud rather than hoped against.
     *
     * <p>A hand-maintained integer somebody forgets is worse than no versioning at all, because it converts a
     * loud failure into a silent one. So the value type's structural fingerprint is recorded under the version,
     * and a version reused across a shape change fails the context, naming the cache and the property to change.
     */
    @Test
    @DisplayName("reusing a key namespace version with a differently-shaped value fails startup")
    void reusingAVersionWithADifferentShapeFailsStartup() {
        new Replica(1).cache().put(new TestPartner("acme"), new PartnerRecord("Acme", 3));

        assertThatThrownBy(() -> new Replica(1, ChangedPartnerRecord.class))
                .isInstanceOf(CacheConfigurationException.class)
                .hasMessageContaining(CACHE)
                .hasMessageContaining("key-namespace.version");
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + DELIVERY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        assertThat(condition.getAsBoolean()).as("condition not met within %s", DELIVERY_TIMEOUT).isTrue();
    }

    /** One process's view of the shared cache: its own registry, its own local tier, one Redis. */
    private static final class Replica {

        private final ru.ludwigandreas.cache.api.LudwigCache<TestPartner, Object> cache;

        Replica(int version) {
            this(version, PartnerRecord.class);
        }

        @SuppressWarnings("unchecked") // The value type is the test's own choice, and it varies in one test only.
        Replica(int version, Class<?> valueType) {
            CacheProperties properties = new CacheProperties();
            properties.getTiers().getShared().setEnabled(true);
            CacheProperties.CacheConfig config = new CacheProperties.CacheConfig();
            config.getTiers().add(CacheTier.SHARED);
            config.getKeyNamespace().setVersion(version);
            config.setTtl(Duration.ofMinutes(5));
            properties.getCaches().put(CACHE, config);

            CacheDefinition<TestPartner, Object> definition =
                    CacheDefinition.<TestPartner, Object>named(CACHE, CachePurpose.PERFORMANCE)
                            .owner("cache-spring-boot-starter-tests")
                            .valueType((Class<Object>) valueType)
                            .keyRenderer(TestPartner::id)
                            .build();
            SharedCacheTier tier = new RedisSharedCacheTier(new StringRedisTemplate(connectionFactory),
                    new ObjectMapper(), listeners, new NoopCacheMetrics(), Clock.systemUTC(),
                    "cache-tests");
            this.cache = LudwigCacheTestSupport
                    .registry(properties, Clock.systemUTC(), new NoopCacheMetrics(), tier, definition)
                    .cache(definition);
        }

        /** Built once, so two calls on one replica are the same cache - as they are in a real process. */
        ru.ludwigandreas.cache.api.LudwigCache<TestPartner, Object> cache() {
            return cache;
        }
    }
}
