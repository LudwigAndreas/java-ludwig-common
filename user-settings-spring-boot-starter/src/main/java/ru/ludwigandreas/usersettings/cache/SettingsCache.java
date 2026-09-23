package ru.ludwigandreas.usersettings.cache;

import java.util.Optional;
import java.util.function.Function;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.SettingsSubject;

/**
 * Cache in front of settings resolution, holding one subject's entire resolved set per entry.
 *
 * <h2>How this differs from the authority cache it is modelled on</h2>
 *
 * <p>{@code AuthorityCache}'s TTL is a security window: it is how long a revoked role keeps working,
 * which is why that one is measured in seconds and why its Javadoc says sizing it is a security
 * decision. <b>This cache's TTL is a performance decision, not a security window.</b> A stale
 * setting means a user briefly sees the old timezone or an old digest preference - a correctness
 * wrinkle with no privilege attached, because nothing here grants access to anything. The default is
 * therefore minutes rather than seconds, and raising it trades freshness for load rather than
 * lengthening an attack window.
 *
 * <p>What the TTL is <em>not</em> is the consistency mechanism. Eviction is: a local write evicts
 * after commit, and a projected change event evicts after the projecting transaction commits. The
 * TTL is the backstop for the case those miss - a dropped event, a consumer that was down - so that
 * a wrong value heals on its own instead of persisting until the next restart.
 *
 * <h2>Why a whole set per entry</h2>
 *
 * <p>The alternative, one entry per (subject, setting), was rejected because it makes {@code getAll}
 * uncacheable: a page needing twenty settings would find twenty entries and still have no way to
 * know whether it had them all. Caching the resolved set means {@code getAll} is one entry,
 * {@code get} reads from that same entry, and eviction is one key rather than one key per setting a
 * subject might have.
 *
 * <h2>Keyed on the subject and the tenant, not on the subject alone</h2>
 *
 * <p>A {@link SettingsSubject} carries the tenant a lookup is confined to, and two lookups for the
 * same subject in different tenants are different questions with different answers - an
 * administrator reading a subject is scoped to the administrator's own tenant. Keying on
 * {@link PrincipalRef} alone would let the first of those answers be served to the second caller,
 * which is a cross-tenant leak wearing a cache's clothing.
 */
public interface SettingsCache {

    Optional<ResolvedSettings> get(SettingsSubject subject);

    /**
     * Resolve through the cache, loading on a miss.
     *
     * <p>Preferred over a {@code get}-then-{@code put} pair because it lets an implementation
     * coalesce concurrent misses for the same subject. Without that, every expiry of a hot subject
     * releases one resolution per in-flight request at once - a self-inflicted thundering herd
     * against the database, arriving exactly when traffic is highest. The default below is the naive
     * behaviour, correct but uncoalesced; {@link CaffeineSettingsCache} overrides it.
     *
     * @param loader invoked at most once per miss; must not return {@code null}
     */
    default ResolvedSettings get(SettingsSubject subject, Function<SettingsSubject, ResolvedSettings> loader) {
        return get(subject).orElseGet(() -> {
            ResolvedSettings loaded = loader.apply(subject);
            put(subject, loaded);
            return loaded;
        });
    }

    void put(SettingsSubject subject, ResolvedSettings settings);

    /** Called when a change for exactly this subject, in this tenant, is known to have happened. */
    void evict(SettingsSubject subject);

    /**
     * Evicts a subject across every tenant they are cached under.
     *
     * <p>The coarse form, for a caller that has a subject and no tenant - an administrative tool, a
     * support action. The event-driven paths do not need it: a change event carries the tenant, so
     * they call {@link #evict(SettingsSubject)} and touch one key.
     */
    void evict(PrincipalRef ref);

    /**
     * Called when something changed that can affect every subject: a tenant-layer or platform-layer
     * write, a reload of the configured defaults, a projection rebuild.
     */
    void evictAll();
}
