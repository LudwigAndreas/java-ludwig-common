package ru.ludwigandreas.job.core.admin.service;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ru.ludwigandreas.cache.api.CachePurpose;
import ru.ludwigandreas.cache.api.CacheSettings;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;
import ru.ludwigandreas.job.core.admin.CacheAdminOperations;
import ru.ludwigandreas.job.core.control.ReplicaControl;
import ru.ludwigandreas.security.admin.AdminOperation;

/**
 * The {@code cacheevict} control: clears every entry of one cache on every replica, and its shared tier where it has
 * one. Clearing a {@code SECURITY} cache - authority, revocation - changes who may do what, so it is privileged.
 * Idempotent: clearing an empty cache does nothing.
 */
public class CacheEvictControl implements ReplicaControl<CacheEvictControl.Arguments> {

    /** The control's name. */
    public static final String NAME = "cacheevict";

    private final LudwigCacheRegistry registry;

    /**
     * The cache to clear.
     *
     * @param cache its name
     */
    public record Arguments(@NotBlank String cache) {
    }

    /**
     * Creates the control.
     *
     * @param registry the caches
     */
    public CacheEvictControl(LudwigCacheRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Class<Arguments> argumentType() {
        return Arguments.class;
    }

    @Override
    public AdminOperation operationFor(Arguments arguments) {
        CacheSettings settings = registry.settings().stream()
                .filter(each -> each.name().equals(arguments.cache())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no cache named '" + arguments.cache() + "'"));
        return settings.purpose() == CachePurpose.SECURITY
                ? CacheAdminOperations.PRIVILEGED_OPERATION : CacheAdminOperations.OPERATE_OPERATION;
    }

    @Override
    public Set<AdminOperation> possibleOperations() {
        return Set.of(CacheAdminOperations.OPERATE_OPERATION, CacheAdminOperations.PRIVILEGED_OPERATION);
    }

    @Override
    public Optional<Object> apply(Arguments arguments) {
        registry.evictAll(arguments.cache());
        return Optional.of(Map.of("cache", arguments.cache(), "cleared", true));
    }
}
