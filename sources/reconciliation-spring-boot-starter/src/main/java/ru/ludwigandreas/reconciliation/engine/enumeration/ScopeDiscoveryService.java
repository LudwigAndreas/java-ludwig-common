package ru.ludwigandreas.reconciliation.engine.enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.reconciliation.api.EnumerationTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the scopes an enumeration has not seen before.
 *
 * <p>Keyset-pages through the integration's scope source and inserts a state row, due immediately,
 * for every key that has none. It never deletes: a scope whose local row has gone is noticed by the
 * walk that tries to reload it, which is the one place that can tell "gone" from "not on this page".
 */
public class ScopeDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ScopeDiscoveryService.class);

    private final ScopeStateService states;

    /**
     * Creates the service.
     *
     * @param states scope state
     */
    public ScopeDiscoveryService(ScopeStateService states) {
        this.states = states;
    }

    /**
     * Pages through a task's scope source once.
     *
     * @param enumeration the enumeration
     * @return how many scopes were registered for the first time
     */
    public int discover(RegisteredEnumeration<?, ?, ?, ?> enumeration) {
        return discoverTyped(enumeration);
    }

    private <S, SK, M, MK> int discoverTyped(RegisteredEnumeration<S, SK, M, MK> enumeration) {
        EnumerationTask<S, SK, M, MK> task = enumeration.task();
        int pageSize = enumeration.settings().discoveryPageSize();
        int registered = 0;
        SK after = null;
        while (true) {
            List<S> page = task.scopes().page(after, pageSize);
            if (page.isEmpty()) {
                break;
            }
            List<String> keys = new ArrayList<>(page.size());
            SK last = after;
            for (S scope : page) {
                last = task.scopes().keyOf(scope);
                keys.add(task.scopeKeyCodec().encode(last));
            }
            registered += states.register(enumeration.name(), keys);
            // A source that returns the same last key again would page forever; treating an
            // unchanged bound as the end turns that mistake into a short listing instead.
            if (page.size() < pageSize || last.equals(after)) {
                break;
            }
            after = last;
        }
        if (registered > 0) {
            log.info("Enumeration '{}' discovered {} new scope(s)", enumeration.name(), registered);
        }
        return registered;
    }
}
