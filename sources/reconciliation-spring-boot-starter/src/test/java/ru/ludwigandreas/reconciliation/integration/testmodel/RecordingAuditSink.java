package ru.ludwigandreas.reconciliation.integration.testmodel;

import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An audit sink that keeps what it is handed.
 *
 * <p>{@code audit-core} composes every {@link AuditSink} bean into the one the module writes to, so
 * declaring this is all it takes to observe the trail without replacing it.
 */
public class RecordingAuditSink implements AuditSink {

    private final List<AuditEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void record(AuditEvent event) {
        events.add(event);
    }

    /** Forgets what was recorded. */
    public void reset() {
        events.clear();
    }

    /**
     * The recorded events with an action.
     *
     * @param action the action
     * @return the events, in order
     */
    public List<AuditEvent> withAction(String action) {
        synchronized (events) {
            return events.stream().filter(event -> action.equals(event.action())).toList();
        }
    }
}
