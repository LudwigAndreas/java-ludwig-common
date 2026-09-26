package ru.ludwigandreas.reconciliation.audit;

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
 * One thing that happened, in the shape every audit sink receives it.
 *
 * <p>One record type for run boundaries, per-record transitions, job state changes, lease events and
 * operator actions, rather than five. The question an audit trail is asked is almost always "what
 * happened around this task, in order", and five shapes means five queries and a manual merge at the
 * moment somebody is trying to work out why a partner was called twice. The platform-wide consolidation
 * is the same argument at the next scale up, which is why this record survived it as the authoring
 * surface while its SPI and its SLF4J logger did not.
 *
 * <p>Renamed from {@code AuditEvent} so that it and {@link ru.ludwigandreas.audit.AuditEvent} can be
 * imported into the same file. {@link Builder#build()} now returns the platform envelope directly - see
 * the note there - so a call site hands the sink an {@code AuditEvent} without naming either type.
 *
 * @param taskName      the task this concerns
 * @param category      {@code RUN}, {@code RECORD}, {@code JOB}, {@code LEASE} or {@code OPERATOR}
 * @param event         the specific event, for example {@code record.quarantined}
 * @param subject       the thing it happened to: a correlation key, a job id, a quota name
 * @param fromState     previous state, for a transition
 * @param toState       new state, for a transition
 * @param detail        free text: an error message, what changed, why an operator did it
 * @param principal     who triggered it, for events that came from the actuator endpoint
 * @param runId         the run it belongs to
 * @param correlationId the correlation id of the unit of work, from the observability starter
 * @param occurredAt    when
 */
public record ReconciliationAuditEvent(String taskName,
                         Category category,
                         String event,
                         String subject,
                         String fromState,
                         String toState,
                         String detail,
                         String principal,
                         UUID runId,
                         String correlationId,
                         Instant occurredAt) {

    /** What kind of thing an event is about. */
    public enum Category {

        /** A run started, finished, was skipped or timed out. */
        RUN,

        /** A staged record changed status. */
        RECORD,

        /** An asynchronous remote job changed state. */
        JOB,

        /** A quota lease was acquired, renewed, released or reclaimed. */
        LEASE,

        /** A human did something through the actuator endpoint. */
        OPERATOR
    }

    /** The resource type every reconciliation event is about. */
    public static final String RESOURCE_TYPE = "sync-task";

    /** Starts building an event for {@code taskName}. */
    public static Builder builder(String taskName, Category category, String event) {
        return new Builder(taskName, category, event);
    }

    /**
     * This event as a platform audit event.
     *
     * <p>{@link Category} becomes an attribute rather than {@link AuditEvent#category()}: that column names
     * the subsystem and these five name a kind of event within it, and collapsing them would make the column
     * mean two different things depending on the row. The {@code audit-003} migration of
     * {@code sync_audit_record} maps it the same way, so a migrated row and a new one are the same shape.
     *
     * <p>The actor is the operator for an {@link Category#OPERATOR} event and {@code system} otherwise,
     * which is the only place in this module where a person is behind an event at all.
     *
     * @return the event
     */
    public AuditEvent toAuditEvent() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("eventCategory", category == null ? null : category.name());
        attributes.put("taskName", taskName);
        attributes.put("subject", subject);
        attributes.put("fromState", fromState);
        attributes.put("toState", toState);
        attributes.put("runId", runId == null ? null : runId.toString());
        return AuditEvent.builder()
                .category(AuditCategories.RECONCILIATION)
                .action(event)
                .occurredAt(occurredAt)
                .actor(principal == null ? Actor.system() : Actor.of(principal, "OPERATOR"))
                .resource(new Resource(RESOURCE_TYPE, taskName, subject))
                .outcome(outcomeOf())
                .correlationId(correlationId)
                .attributes(attributes)
                .build();
    }

    /**
     * The outcome, inferred from the event name.
     *
     * <p>Inferred rather than carried, because the record never had an outcome component and adding one
     * would mean revisiting every one of its call sites to say something they all already say in the event
     * name. The two names that matter are the ones an auditor filters on: a quarantined record and a failed
     * run are not successes, and a trail in which they were would answer "did anything go wrong last
     * Tuesday" with "no".
     */
    private AuditOutcome outcomeOf() {
        if (event == null) {
            return AuditOutcome.success();
        }
        if (event.endsWith(".failed") || event.endsWith(".timed-out") || event.endsWith(".reclaimed")) {
            return AuditOutcome.failure(detail);
        }
        if (event.endsWith(".quarantined") || event.endsWith(".skipped")) {
            return AuditOutcome.partial(detail);
        }
        return AuditOutcome.success();
    }

    /**
     * Builder for {@link ReconciliationAuditEvent}.
     *
     * <p>Hand-written rather than generated because the three required fields are constructor
     * arguments and the rest are genuinely optional - which is exactly the shape Lombok's
     * {@code @Builder} cannot express without making everything optional.
     */
    public static final class Builder {

        private final String taskName;
        private final Category category;
        private final String event;
        private String subject;
        private String fromState;
        private String toState;
        private String detail;
        private String principal;
        private UUID runId;
        private String correlationId;

        private Builder(String taskName, Category category, String event) {
            this.taskName = taskName;
            this.category = category;
            this.event = event;
        }

        /** The thing this happened to. */
        public Builder subject(String value) {
            this.subject = value;
            return this;
        }

        /** The states this transition moved between. */
        public Builder transition(Object from, Object to) {
            this.fromState = from == null ? null : from.toString();
            this.toState = to == null ? null : to.toString();
            return this;
        }

        /** Free text: an error message, what changed, why. */
        public Builder detail(String value) {
            this.detail = value;
            return this;
        }

        /** Who triggered it. */
        public Builder principal(String value) {
            this.principal = value;
            return this;
        }

        /** The run it belongs to. */
        public Builder runId(UUID value) {
            this.runId = value;
            return this;
        }

        /** The correlation id of the unit of work. */
        public Builder correlationId(String value) {
            this.correlationId = value;
            return this;
        }

        /**
         * Builds the event as the platform envelope, stamped with the current instant.
         *
         * <p>Returns {@link AuditEvent} and not this module's record on purpose: every call site in this
         * module builds an event only to hand it straight to the sink, and having {@code build()} answer
         * with the envelope keeps a {@code .toAuditEvent()} off some forty call sites without hiding
         * anything - {@link #buildTyped()} is there for a caller that wants the typed record.
         *
         * @return the platform event
         */
        public AuditEvent build() {
            return buildTyped().toAuditEvent();
        }

        /**
         * Builds this module's own record, for a caller that wants its named components.
         *
         * @return the typed event
         */
        public ReconciliationAuditEvent buildTyped() {
            return new ReconciliationAuditEvent(taskName, category, event, subject, fromState, toState,
                    detail, principal, runId, correlationId, Instant.now());
        }
    }
}
