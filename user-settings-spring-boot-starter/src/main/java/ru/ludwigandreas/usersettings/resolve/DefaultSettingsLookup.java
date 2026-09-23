package ru.ludwigandreas.usersettings.resolve;

import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.audit.SettingsAuditRecorder;
import ru.ludwigandreas.usersettings.cache.SettingsCache;
import ru.ludwigandreas.usersettings.exception.TenantUnresolvableException;
import ru.ludwigandreas.usersettings.metrics.SettingsMetrics;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;

/**
 * The {@link SettingsLookup} both modes publish: resolution with the access check, the cache, the
 * administrative-read audit and metrics wrapped around it.
 *
 * <p>Owner mode and projection mode differ in where the rows come from and in whether writes exist.
 * Neither difference reaches this class, which is why a consuming service can be written once and
 * moved between modes by configuration.
 *
 * <h2>Why {@code get} goes through {@code getAll}</h2>
 *
 * <p>Reading one setting resolves them all, and that is deliberate rather than wasteful. A miss
 * costs one query either way, because resolution is a single query for every scope at once; a hit
 * costs nothing. What it buys is that the N+1 is unrepresentable: code that reads five settings in a
 * row, or reads one inside a loop over recipients, cannot accidentally issue five queries, because
 * there is no code path that fetches less than everything.
 */
@RequiredArgsConstructor
public class DefaultSettingsLookup implements SettingsLookup {

    private final SettingsResolutionEngine engine;
    private final SettingsCache cache;
    private final SettingsAccessPolicy accessPolicy;
    private final SettingsTenantResolver tenantResolver;
    private final SettingDefinitionRegistry registry;
    private final SettingsAuditRecorder audit;
    private final SettingsMetrics metrics;

    @Override
    public <T> T get(PrincipalRef ref, SettingDefinition<T> definition) {
        return get(subjectFor(ref), definition);
    }

    @Override
    public <T> T get(SettingsSubject subject, SettingDefinition<T> definition) {
        // Checked before the lookup, not after: a definition this service never registered must fail
        // as "unknown setting" rather than silently resolving to the default of a similarly-named one.
        return getAll(subject).get(registry.require(definition));
    }

    @Override
    public ResolvedSettings getAll(PrincipalRef ref) {
        return getAll(subjectFor(ref));
    }

    @Override
    public ResolvedSettings getAll(SettingsSubject subject) {
        SettingsAccess access = accessPolicy.check(subject);

        // The flag is how hit and miss are still told apart while the cache owns the load: the loader
        // body runs only on a miss, and only for the one caller that wins the per-key race.
        AtomicBoolean loaded = new AtomicBoolean();
        ResolvedSettings settings = cache.get(subject, key -> {
            loaded.set(true);
            return engine.resolve(key);
        });

        if (loaded.get()) {
            metrics.recordCacheMiss();
        } else {
            metrics.recordCacheHit();
        }

        if (access == SettingsAccess.ADMIN) {
            // Audited whether the read was served from the cache or not: what is being recorded is
            // that this administrator looked at this subject, which happened either way.
            audit.recordAdminRead(subject);
            metrics.recordAdminRead();
        }
        return settings;
    }

    /**
     * The subject, with the tenant the lookup is confined to.
     *
     * @throws TenantUnresolvableException rather than falling back to an unscoped read. See that
     *                                     exception for why the refusal is the safe direction.
     */
    private SettingsSubject subjectFor(PrincipalRef ref) {
        return SettingsSubjects.require(tenantResolver, ref);
    }
}
