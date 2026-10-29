package ru.ludwigandreas.job.core.admin.service;

import java.time.Duration;
import java.util.List;
import ru.ludwigandreas.cache.api.CachePurpose;
import ru.ludwigandreas.cache.api.CacheSettings;
import ru.ludwigandreas.cache.api.CacheTier;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;

/**
 * The {@code caches} area's listing: every registered cache's settings, never an entry.
 */
public class CacheAdminService {

    private final LudwigCacheRegistry registry;

    /**
     * Creates the service.
     *
     * @param registry the caches
     */
    public CacheAdminService(LudwigCacheRegistry registry) {
        this.registry = registry;
    }

    /**
     * The registered caches.
     *
     * @return one entry per cache, in declaration order
     */
    public List<CacheView> caches() {
        return registry.settings().stream().map(CacheView::of).toList();
    }

    /**
     * One cache as an operator sees it.
     *
     * @param name    the cache
     * @param owner   the module that declared it
     * @param purpose what its TTL means
     * @param tiers   where entries live
     * @param ttl     how long an entry lives
     * @param enabled whether it caches at all
     */
    public record CacheView(String name, String owner, CachePurpose purpose, List<CacheTier> tiers, Duration ttl,
                            boolean enabled) {

        static CacheView of(CacheSettings settings) {
            return new CacheView(settings.name(), settings.owner(), settings.purpose(), settings.tiers(),
                    settings.ttl(), settings.enabled());
        }
    }
}
