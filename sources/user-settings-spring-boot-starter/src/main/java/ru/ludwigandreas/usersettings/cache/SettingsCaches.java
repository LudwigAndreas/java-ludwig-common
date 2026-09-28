package ru.ludwigandreas.usersettings.cache;

import java.time.Duration;
import ru.ludwigandreas.cache.api.CacheDefinition;
import ru.ludwigandreas.cache.api.CachePurpose;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.SettingsSubject;

/**
 * The platform's {@code settings} cache, declared.
 *
 * <h2>What used to be here</h2>
 *
 * <p>A {@code SettingsCache} interface, a {@code CaffeineSettingsCache}, a {@code NoopSettingsCache} and an
 * {@code AfterCommitEviction} helper - the first three being, to within their key and value types, the same
 * classes {@code security-spring-boot-starter} had written for its authority cache, down to the
 * {@code (Duration ttl, long maximumSize)} constructor and the thundering-herd paragraph. The fourth had a
 * second copy too, hand-registered inside {@code IdentityProjectionService}. All of it is now
 * {@code cache-spring-boot-starter}'s; what stayed here is what was never generic.
 *
 * <h2>The TTL is a performance decision, not a security window</h2>
 *
 * <p>This is the distinction the shared module is built around, and it is the reason one shared default TTL
 * would have been wrong for one of the two caches whatever number it was. {@code AuthorityCaches} declares
 * {@link CachePurpose#SECURITY}: its TTL is how long a revoked role keeps working, which is why it is
 * measured in seconds and why a startup ceiling applies to it. This one declares
 * {@link CachePurpose#PERFORMANCE}: a stale setting means a user briefly sees the old timezone or an old
 * digest preference - a correctness wrinkle with no privilege attached, because nothing here grants access to
 * anything. The default is therefore minutes, raising it trades freshness for load rather than lengthening an
 * attack window, and stale-while-revalidate is available here and refused there.
 *
 * <p>What the TTL is <em>not</em>, in either case, is the consistency mechanism. Eviction is: a local write
 * evicts after commit, and a projected change event evicts after the projecting transaction commits. The TTL
 * is the backstop for the case those miss - a dropped event, a consumer that was down - so that a wrong value
 * heals on its own instead of persisting until the next restart.
 *
 * <h2>Keyed on the subject and the tenant, not on the subject alone</h2>
 *
 * <p>A {@link SettingsSubject} carries the tenant a lookup is confined to, and two lookups for the same
 * subject in different tenants are different questions with different answers - an administrator reading a
 * subject is scoped to the administrator's own tenant. Keying on {@link PrincipalRef} alone would let the
 * first of those answers be served to the second caller, which is a cross-tenant leak wearing a cache's
 * clothing. {@link #renderKey} preserves that in the shared tier too.
 */
public final class SettingsCaches {

    /** The cache name: the YAML key under {@code ludwig.cache.caches} and the {@code cache} meter tag. */
    public static final String NAME = "settings";

    /** Unchanged from the value this module carried before the consolidation. */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private static final long DEFAULT_MAXIMUM_SIZE = 10_000L;

    private SettingsCaches() {
    }

    /**
     * The declaration this module publishes as a bean.
     *
     * <p>{@code requiresScanEviction} is declared because {@link #evictAcrossTenants} exists: a support path
     * that holds a principal and no tenant has to evict that principal wherever they are cached. Declaring it
     * here rather than leaving it to configuration also means a deployment cannot add the shared tier to this
     * cache - the startup validator refuses the combination, because eviction by predicate against Redis needs
     * either a {@code KEYS} sweep or a secondary index.
     *
     * @return the definition; a deployment overrides its TTL and size under
     *         {@code ludwig.cache.caches.settings}
     */
    public static CacheDefinition<SettingsSubject, ResolvedSettings> definition() {
        return CacheDefinition.<SettingsSubject, ResolvedSettings>named(NAME, CachePurpose.PERFORMANCE)
                .owner("user-settings-spring-boot-starter")
                .defaultTtl(DEFAULT_TTL)
                .defaultMaximumSize(DEFAULT_MAXIMUM_SIZE)
                .requiresScanEviction(true)
                .valueType(ResolvedSettings.class)
                .keyRenderer(SettingsCaches::renderKey)
                .build();
    }

    /**
     * Evicts a subject across every tenant they are cached under.
     *
     * <h2>This walks the whole key set, and the name is where that is said</h2>
     *
     * <p>The previous API offered it as {@code evict(PrincipalRef)}, an innocuous-looking overload sitting
     * beside the O(1) {@code evict(SettingsSubject)}, with the cost buried in the implementation's javadoc. A
     * generic method that is secretly O(n) is a performance bug waiting for its second caller, so the
     * platform's cache calls the operation {@link LudwigCache#evictByScan} and this wrapper keeps the reason
     * it is acceptable in one place: it is the coarse form, for a caller that has a subject and no tenant - an
     * administrative tool, a support action. The event-driven and write-driven paths never need it, because a
     * change event carries the tenant and they evict one key.
     *
     * @param cache the settings cache
     * @param ref   the subject to evict everywhere
     * @return how many entries were dropped
     */
    public static int evictAcrossTenants(LudwigCache<SettingsSubject, ResolvedSettings> cache,
                                         PrincipalRef ref) {
        return cache.evictByScan(subject -> subject.ref().equals(ref));
    }

    /**
     * Renders a subject into the tail of a shared key.
     *
     * <p>Tenant, then principal type, then subject. The type is included for the reason
     * {@link PrincipalRef} exists at all - subject namespaces are independent, and a partner id equal to some
     * user's {@code sub} must not collide - and the tenant is included because leaving it out is the
     * cross-tenant leak described on this class.
     */
    private static String renderKey(SettingsSubject subject) {
        return subject.tenantId() + '|' + subject.ref().type().name() + ':' + subject.ref().subject();
    }
}
