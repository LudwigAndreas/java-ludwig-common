package ru.ludwigandreas.idempotency.audit;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.Resource;

/**
 * One thing that happened to a claim, in the form an audit sink receives it.
 *
 * <p>A typed record as the authoring surface with a {@code toAuditEvent()} that flattens into the
 * platform envelope, exactly as {@code IngestAuditEvent} and {@code ExportAuditEvent} do: a caller
 * assembling ten positional components out of a {@code Map<String, Object>} is worse than a record with
 * names, and a sink switching over a growing hierarchy of event types is worse than one shape.
 *
 * <h2>Which events are worth a row, and which are not</h2>
 *
 * <p>Two, and it is a short list on purpose. An audit trail is retained for years and read by people
 * looking for something specific; filling it with one row per successful claim would mean one row per
 * request on every protected endpoint, which buries the two events that matter under traffic the access
 * log already has.
 *
 * <ul>
 *   <li>a <b>replay</b>, because a caller received a response that this service did not produce for that
 *       call. When somebody later asks "why does the log show one order and the client show two
 *       confirmations", this row is the answer;</li>
 *   <li>a <b>fingerprint mismatch</b>, because it is the one outcome that means a client is broken, and
 *       because the question it raises - which caller, how often, against which endpoint - is exactly the
 *       question an audit trail answers and a counter does not.</li>
 * </ul>
 *
 * <p>Neither carries the request body or the fingerprints. The fingerprints are hashes of payloads and
 * publishing one would let a reader of the trail confirm a guess about a request's contents; the key was
 * sent by the client and is what ties the row to the client's own logs.
 *
 * @param at        when it happened
 * @param action    what happened; one of the constants below
 * @param scope     the scope the key was claimed in
 * @param key       the caller's key
 * @param owner     the request that holds the claim, or {@code null} when the event is not about one
 * @param actor     who the call was made by, or {@code null} for the system actor
 * @param details   anything else worth recording
 */
public record IdempotencyAuditEvent(Instant at, String action, String scope, String key, UUID owner,
                                    Actor actor, Map<String, Object> details) {

    /** A duplicate was answered with the original response instead of re-running the work. */
    public static final String REPLAYED = "replayed";

    /** A key was presented with a different request from the one it was claimed for. */
    public static final String FINGERPRINT_MISMATCH = "fingerprint-mismatch";

    /** The resource type these events are about. */
    public static final String RESOURCE_TYPE = "idempotency-key";

    /** Normalises the details map and the timestamp. */
    public IdempotencyAuditEvent {
        at = at == null ? Instant.now() : at;
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /** A replay. */
    public static IdempotencyAuditEvent replayed(String scope, String key, UUID owner, Actor actor) {
        return new IdempotencyAuditEvent(Instant.now(), REPLAYED, scope, key, owner, actor, Map.of());
    }

    /** A key reused for a different request. */
    public static IdempotencyAuditEvent fingerprintMismatch(String scope, String key, UUID owner,
                                                            Actor actor) {
        return new IdempotencyAuditEvent(Instant.now(), FINGERPRINT_MISMATCH, scope, key, owner, actor,
                Map.of());
    }

    /**
     * This event as a platform audit event.
     *
     * <p>A mismatch is a {@link AuditOutcome.Status#DENIED} rather than a failure: the service refused
     * the request on purpose, and it refused it correctly. Recording it as a failure would put it in the
     * same bucket as the things that broke, which is the distinction an incident responder needs first -
     * "we refused them" and "we broke" look identical in a boolean and lead to opposite investigations.
     *
     * <p>A replay is a success, because it is: the caller got the right answer.
     *
     * @return the event
     */
    public AuditEvent toAuditEvent() {
        Map<String, Object> attributes = new LinkedHashMap<>(details);
        attributes.put("scope", scope);
        // The key, not a hash of it: the client chose it, sent it in a header, and has it in its own
        // logs, so it is the one value that lets the two sides of a dispute be compared. It is not
        // personal data by construction, and a client that puts personal data in one has made that
        // choice in a header that every proxy on the path has already logged.
        attributes.put("idempotencyKey", key);
        if (owner != null) {
            attributes.put("ownerRequestId", owner.toString());
        }
        return AuditEvent.builder()
                .category(AuditCategories.IDEMPOTENCY)
                .action(AuditCategories.IDEMPOTENCY + "." + action)
                .occurredAt(at)
                .actor(actor == null ? Actor.system() : actor)
                .resource(new Resource(RESOURCE_TYPE, scope, null))
                .outcome(FINGERPRINT_MISMATCH.equals(action)
                        ? AuditOutcome.denied("the key was claimed for a different request")
                        : AuditOutcome.success())
                .attributes(attributes)
                .build();
    }
}
