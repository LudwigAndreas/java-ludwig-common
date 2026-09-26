package ru.ludwigandreas.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes the trail to a dedicated logger, one line per event.
 *
 * <p>Replaces the seven {@code Slf4j*} audit implementations this platform used to carry, which
 * differed only in field order and logger name while all making the same argument for existing: an
 * estate whose log pipeline already is its audit pipeline needs somewhere for the trail to go, and
 * it should be one grep-able place.
 *
 * <p>The logger is {@value #LOGGER_NAME} and not this class's own name, so a deployment can route the
 * whole trail to its own appender, its own index and its own retention without filtering on message
 * content. The old per-module logger names ({@code ru.ludwigandreas.export.audit},
 * {@code ludwig.restclient.audit} and the rest) are gone, which is a change a deployment that greps
 * for one of them will notice - each module's README says so.
 *
 * <p>Positional placeholders rather than a formatted string, because
 * {@code observability-spring-boot-starter}'s JSON encoder turns the arguments into queryable fields;
 * building the string here would give that up and gain nothing.
 *
 * <h2>Levels</h2>
 *
 * <p>INFO for anything that happened, WARN for a denial or a failure. Not DEBUG: the events somebody
 * reconstructs a morning from must not live at a level that is switched off in every environment
 * where they would have been needed. Not everything at WARN either - {@code Slf4jAccessAuditLogger}
 * had this right, and logging successful grants at a level operators watch buries the denials under
 * normal traffic.
 */
public class Slf4jAuditSink implements AuditSink {

    /** The logger every audit line is written to. */
    public static final String LOGGER_NAME = "ru.ludwigandreas.audit";

    private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER_NAME);

    /**
     * One fixed field order for every event, because the consumer is a log pipeline rather than a
     * person reading a sentence.
     */
    private static final String FORMAT = "audit id={} at={} category={} action={} outcome={}"
            + " actor={} actorType={} onBehalfOf={} resourceType={} resourceId={}"
            + " correlationId={} traceId={} reason={} attributes={}";

    @Override
    public void record(AuditEvent event) {
        AuditOutcome outcome = event.outcome();
        Actor actor = event.actor();
        Resource resource = event.resource();
        boolean adverse = outcome.status() == AuditOutcome.Status.DENIED
                || outcome.status() == AuditOutcome.Status.FAILURE;
        if (adverse) {
            AUDIT.warn(FORMAT, event.id(), event.occurredAt(), event.category(), event.action(),
                    outcome.status(), actor.subject(), actor.principalType(), actor.onBehalfOf(),
                    type(resource), id(resource), event.correlationId(), event.traceId(),
                    outcome.reason(), event.attributes());
            return;
        }
        if (AUDIT.isInfoEnabled()) {
            AUDIT.info(FORMAT, event.id(), event.occurredAt(), event.category(), event.action(),
                    outcome.status(), actor.subject(), actor.principalType(), actor.onBehalfOf(),
                    type(resource), id(resource), event.correlationId(), event.traceId(),
                    outcome.reason(), event.attributes());
        }
    }

    private static String type(Resource resource) {
        return resource == null ? null : resource.type();
    }

    private static String id(Resource resource) {
        return resource == null ? null : resource.id();
    }
}
