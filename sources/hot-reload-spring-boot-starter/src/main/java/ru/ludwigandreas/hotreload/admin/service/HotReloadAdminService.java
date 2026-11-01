package ru.ludwigandreas.hotreload.admin.service;

import java.time.Instant;
import java.util.List;
import ru.ludwigandreas.hotreload.core.ReloadableSourceRegistry;
import ru.ludwigandreas.hotreload.core.SourceReloadCoordinator;

/**
 * The {@code hotreload} area's listing: every reloadable source this replica watches, never a value.
 */
public class HotReloadAdminService {

    private final ReloadableSourceRegistry registry;
    private final SourceReloadCoordinator coordinator;

    /**
     * Creates the service.
     *
     * @param registry    the sources
     * @param coordinator what each last reloaded
     */
    public HotReloadAdminService(ReloadableSourceRegistry registry, SourceReloadCoordinator coordinator) {
        this.registry = registry;
        this.coordinator = coordinator;
    }

    /**
     * The registered sources.
     *
     * @return one entry per source, in registration order
     */
    public List<SourceView> sources() {
        return registry.entries().stream().map(entry -> new SourceView(entry.id(), entry.kind(),
                coordinator.lastReloaded(entry.id()).orElse(null))).toList();
    }

    /**
     * One source as an operator sees it.
     *
     * @param id               the source's id
     * @param kind             what it is read from
     * @param lastReloadedHere when it last reloaded with changed content on the replica that answered, or null;
     *                         other replicas may differ
     */
    public record SourceView(String id, ReloadableSourceRegistry.Kind kind, Instant lastReloadedHere) {
    }
}
