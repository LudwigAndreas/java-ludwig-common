package ru.ludwigandreas.cache.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.cache.config.CacheProperties;
import ru.ludwigandreas.cache.test.LudwigCacheTestSupport;

/**
 * Eviction: when it happens relative to a transaction, and what a two-tier eviction is actually made of.
 */
class CacheEvictionTest {

    private static final TestKey ALICE = new TestKey("alice");

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("an eviction registered inside a transaction does not happen until the commit")
    void evictionWaitsForTheCommit() {
        // Evicting inside the transaction opens a window in which another thread misses, re-reads the row as
        // it was BEFORE the uncommitted change, and repopulates the cache with the stale value - which then
        // survives until the TTL, long after the write everybody believes took effect.
        LudwigCache<TestKey, TestValue> cache = LudwigCacheTestSupport.cache(CacheFixtures.grants());
        cache.put(ALICE, new TestValue("granted", 1));
        TransactionSynchronizationManager.initSynchronization();

        cache.evictAfterCommit(ALICE);

        assertThat(cache.get(ALICE)).as("still cached while the transaction is open")
                .contains(new TestValue("granted", 1));

        TransactionSynchronizationUtils.triggerAfterCommit();

        assertThat(cache.get(ALICE)).as("evicted once the transaction committed").isEmpty();
    }

    /**
     * The case that matters most, and the one an eager eviction gets wrong in the other direction.
     *
     * <p>Evicting eagerly on a transaction that commits is correct-but-racy. Evicting on a transaction that
     * rolls back caches nothing and hides a bug: the write never happened, the cached value was right all
     * along, and it was discarded for nothing.
     */
    @Test
    @DisplayName("an eviction registered inside a transaction that rolls back never happens")
    void evictionDoesNotHappenOnRollback() {
        LudwigCache<TestKey, TestValue> cache = LudwigCacheTestSupport.cache(CacheFixtures.grants());
        cache.put(ALICE, new TestValue("granted", 1));
        TransactionSynchronizationManager.initSynchronization();

        cache.evictAfterCommit(ALICE);
        // What a rollback looks like from here: the synchronizations are discarded without afterCommit ever
        // being triggered.
        TransactionSynchronizationManager.clearSynchronization();

        assertThat(cache.get(ALICE)).as("the value was correct all along")
                .contains(new TestValue("granted", 1));
    }

    @Test
    @DisplayName("with no transaction the eviction happens immediately - correct, not a fallback")
    void evictionHappensImmediatelyWithoutATransaction() {
        LudwigCache<TestKey, TestValue> cache = LudwigCacheTestSupport.cache(CacheFixtures.grants());
        cache.put(ALICE, new TestValue("granted", 1));

        cache.evictAfterCommit(ALICE);

        assertThat(cache.get(ALICE)).isEmpty();
    }

    /**
     * A two-tier eviction is three things and this pins all three.
     *
     * <p>The one that is easy to forget is the third: deleting the shared entry does nothing about the local
     * copies every other replica holds, which is what the invalidation announcement is for.
     */
    @Test
    @DisplayName("a two-tier eviction drops the local entry, deletes the shared one and announces the key")
    void twoTierEvictionDoesAllThree() {
        FakeSharedCacheTier tier = new FakeSharedCacheTier();
        LudwigCache<TestKey, TestValue> cache = sharedCache(tier, new RecordingCacheMetrics());
        cache.put(ALICE, new TestValue("granted", 1));
        assertThat(tier.holds("alice")).isTrue();

        cache.evict(ALICE);

        assertThat(cache.get(ALICE)).isEmpty();
        assertThat(tier.holds("alice")).isFalse();
        assertThat(tier.published()).containsExactly("alice");
    }

    /**
     * A failed shared eviction is an incident, and it is counted rather than thrown.
     *
     * <p>Thrown would be worse than useless: the eviction runs in {@code afterCommit}, where the business
     * transaction is already durable, so an exception there cannot undo anything and would only turn a
     * successful write into a failed response. What it must not be is silent - if the shared entry survives,
     * <em>every</em> replica keeps serving the stale value, which is strictly worse than the single-tier case
     * where only the writing replica was wrong.
     */
    @Test
    @DisplayName("a failed shared eviction is counted, does not throw, and the local eviction still happened")
    void failedSharedEvictionIsCountedAndLocalStillEvicts() {
        FakeSharedCacheTier tier = new FakeSharedCacheTier();
        RecordingCacheMetrics metrics = new RecordingCacheMetrics();
        LudwigCache<TestKey, TestValue> cache = sharedCache(tier, metrics);
        cache.put(ALICE, new TestValue("granted", 1));
        tier.failDeletes(true);

        assertThatCode(() -> cache.evict(ALICE)).doesNotThrowAnyException();

        assertThat(metrics.sharedEvictionFailures()).isEqualTo(1);
        assertThat(tier.holds("alice")).as("the shared entry survived, which is why this is an incident")
                .isTrue();

        // With the shared tier out of the way, the local eviction is visible: it did happen, and it happened
        // whether or not the shared delete succeeded - this replica being right is worth having even when the
        // others cannot be told.
        tier.available(false);
        assertThat(cache.get(ALICE)).as("the local eviction is not conditional on the shared one").isEmpty();

        // And here is what the failure costs, stated as a test rather than as a warning: with the tier
        // reachable again, the next read misses locally, finds the surviving shared entry and promotes it
        // straight back. A failed shared eviction does not leave one replica stale - it leaves every replica
        // stale, including the one that performed the eviction.
        tier.available(true);
        assertThat(cache.get(ALICE)).contains(new TestValue("granted", 1));
    }

    @Test
    @DisplayName("an invalidation from another replica drops the local entry without a second shared delete")
    void remoteInvalidationDropsTheLocalEntry() {
        FakeSharedCacheTier tier = new FakeSharedCacheTier();
        LudwigCache<TestKey, TestValue> cache = sharedCache(tier, new RecordingCacheMetrics());
        cache.put(ALICE, new TestValue("granted", 1));
        tier.failDeletes(true); // Nothing here may delete: the announcing replica already did.

        tier.deliverInvalidation("alice");

        // Read with the shared tier out of the way, because otherwise a read would simply re-promote the
        // entry the announcing replica is assumed to have deleted. What is under test is the local drop.
        tier.available(false);
        assertThat(cache.get(ALICE)).isEmpty();
    }

    private LudwigCache<TestKey, TestValue> sharedCache(FakeSharedCacheTier tier,
                                                        RecordingCacheMetrics metrics) {
        CacheProperties properties = CacheFixtures.sharedPropertiesFor(CacheFixtures.GRANTS);
        return LudwigCacheTestSupport
                .registry(properties, Clock.systemUTC(), metrics, tier, CacheFixtures.grants())
                .cache(CacheFixtures.grants());
    }
}
