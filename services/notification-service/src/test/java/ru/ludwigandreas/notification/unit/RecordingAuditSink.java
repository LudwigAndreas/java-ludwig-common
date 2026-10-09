package ru.ludwigandreas.notification.unit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditSink;

/**
 * An {@link AuditSink} that remembers what it was given, so a test can assert that something was
 * audited and with what.
 *
 * <h2>Why a recording sink rather than a Mockito mock</h2>
 *
 * <p>A mock would prove {@code record} was called. These tests need the <em>content</em> of the
 * event — that the audience was recorded as a kind and a value and that no resolved subject list
 * came with it — and asserting on a captured argument through a mock is the same thing with more
 * indirection and a worse failure message.
 *
 * <p><b>It is still the one sink.</b> No new interface exists - this implements the platform's own
 * {@code AuditSink}, so the ArchUnit rule forbidding a module-local audit SPI stays satisfied and the
 * service under test calls {@code record} exactly as it does in production.
 *
 * <p><b>Injected directly into a unit test, never registered as a Spring bean.</b> Substituting it in
 * the integration context was tried twice and abandoned: the platform already publishes a
 * {@code @Primary} {@code AuditSink}, so adding another gives "more than one 'primary' bean found"
 * and breaks the shared context that thirteen other integration tests depend on. The audit assertions
 * do not need a context - they are about which method was called and what it was given - so they are
 * made here, where they cost nothing and destabilise nothing.
 *
 * <p>It also deliberately does not throw. Whether a sink failure fails the caller is
 * {@code AuditFailurePolicy}'s decision, resolved from configuration; a test double that threw would
 * be testing that policy rather than the service.
 *
 */
public class RecordingAuditSink implements AuditSink {

    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(AuditEvent event) {
        events.add(event);
    }

    public void clear() {
        events.clear();
    }

    public List<AuditEvent> events() {
        return List.copyOf(events);
    }

    /** Every action recorded, in order, for a containment assertion that reads well on failure. */
    public List<String> actions() {
        List<String> actions = new ArrayList<>(events.size());
        for (AuditEvent event : events) {
            actions.add(event.action());
        }
        return actions;
    }

    /**
     * The attributes of the one event with this action.
     *
     * @throws AssertionError when no event, or more than one, carries that action — an ambiguous
     *                        assertion is worse than a failing one
     */
    public Map<String, Object> attributesOf(String action) {
        List<AuditEvent> matching = events.stream()
                .filter(event -> action.equals(event.action()))
                .toList();
        if (matching.size() != 1) {
            throw new AssertionError("expected exactly one '" + action + "' event but recorded "
                    + actions());
        }
        Map<String, Object> attributes = matching.get(0).attributes();
        return attributes == null ? Map.of() : attributes;
    }
}
