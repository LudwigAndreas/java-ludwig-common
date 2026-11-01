package ru.ludwigandreas.hotreload.admin.service;

import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ru.ludwigandreas.hotreload.admin.HotReloadAdminOperations;
import ru.ludwigandreas.hotreload.core.ReloadOutcome;
import ru.ludwigandreas.hotreload.core.ReloadableSourceRegistry;
import ru.ludwigandreas.job.core.control.ReplicaControl;
import ru.ludwigandreas.security.operation.Operation;

/**
 * The {@code hotreloadreload} control: reloads one source on every replica, through the coordinator a watcher uses,
 * so the diff, the last-known-good rule, the audit and the listeners are exactly a watched reload's. Each replica
 * reports {@code reloaded}, {@code unchanged} or {@code rejected}; a rejected reload is still an acknowledged one.
 * Idempotent: reloading unchanged content does nothing.
 */
public class HotReloadReloadControl implements ReplicaControl<HotReloadReloadControl.Arguments> {

    /** The control's name. */
    public static final String NAME = "hotreloadreload";

    private final ReloadableSourceRegistry registry;

    /**
     * The source to reload.
     *
     * @param source its id, as the listing shows it
     */
    public record Arguments(@NotBlank String source) {
    }

    /**
     * Creates the control.
     *
     * @param registry the sources
     */
    public HotReloadReloadControl(ReloadableSourceRegistry registry) {
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
    public Operation operationFor(Arguments arguments) {
        entry(arguments.source());
        return HotReloadAdminOperations.SOURCE_RELOAD_OPERATION;
    }

    @Override
    public Set<Operation> possibleOperations() {
        return Set.of(HotReloadAdminOperations.SOURCE_RELOAD_OPERATION);
    }

    @Override
    public Optional<Object> apply(Arguments arguments) {
        ReloadOutcome outcome = entry(arguments.source()).reload().get();
        return Optional.of(Map.of("source", arguments.source(), "outcome", outcome.name().toLowerCase(Locale.ROOT)));
    }

    private ReloadableSourceRegistry.Entry entry(String source) {
        return registry.find(source)
                .orElseThrow(() -> new IllegalArgumentException("no reloadable source '" + source + "'"));
    }
}
