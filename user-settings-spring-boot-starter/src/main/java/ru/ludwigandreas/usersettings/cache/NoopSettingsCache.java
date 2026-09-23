package ru.ludwigandreas.usersettings.cache;

import java.util.Optional;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.SettingsSubject;

/**
 * Always-miss cache: every lookup re-resolves. Registered when Caffeine is absent from the classpath
 * or {@code ludwig.user-settings.cache.enabled=false}.
 *
 * <p>Correct, just slower - and unlike the authority cache, where not caching is the safe direction
 * to fail in, here both directions are merely performance. This exists so that the module has no
 * hard dependency on Caffeine and so that a test can assert on resolution behaviour without a cache
 * in the way.
 */
public class NoopSettingsCache implements SettingsCache {

    @Override
    public Optional<ResolvedSettings> get(SettingsSubject subject) {
        return Optional.empty();
    }

    @Override
    public void put(SettingsSubject subject, ResolvedSettings settings) {
        // nothing to store
    }

    @Override
    public void evict(SettingsSubject subject) {
        // nothing to evict
    }

    @Override
    public void evict(PrincipalRef ref) {
        // nothing to evict
    }

    @Override
    public void evictAll() {
        // nothing to evict
    }
}
