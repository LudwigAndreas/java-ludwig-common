package ru.ludwigandreas.hotreload.admin;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The {@code hotreload} administrative area: the reloadable configuration and secret sources, and reloading one on
 * every replica.
 */
public final class HotReloadAdminOperations implements OperationContributor {

    /** See the reloadable sources, never a value. */
    public static final String SOURCE_READ = "hotreload.source.read";

    /** Reload a source on every replica. */
    public static final String SOURCE_RELOAD = "hotreload.source.reload";

    private static final String KEY = "ludwig.hotreload.admin.";

    /** {@link #SOURCE_RELOAD} as a catalog entry, which the reload control declares. */
    public static final Operation SOURCE_RELOAD_OPERATION =
            Operation.admin(SOURCE_RELOAD, Sensitivity.CHANGE, KEY + SOURCE_RELOAD);

    @Override
    public Collection<Operation> operations() {
        return List.of(Operation.admin(SOURCE_READ, Sensitivity.READ, KEY + SOURCE_READ),
                SOURCE_RELOAD_OPERATION);
    }
}
