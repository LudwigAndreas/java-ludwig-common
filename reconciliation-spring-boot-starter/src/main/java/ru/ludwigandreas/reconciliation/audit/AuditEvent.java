package ru.ludwigandreas.reconciliation.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * One thing that happened, in the shape every audit sink receives it.
 *
 * <p>One record type for run boundaries, per-record transitions, job state changes, lease events and
 * operator actions, rather than five. The question an audit trail is asked is almost always "what
 * happened around this task, in order", and five shapes means five queries and a manual merge at the
 * moment somebody is trying to work out why a partner was called twice.
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
public record AuditEvent(String taskName,
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

    /** Starts building an event for {@code taskName}. */
    public static Builder builder(String taskName, Category category, String event) {
        return new Builder(taskName, category, event);
    }

    /**
     * Builder for {@link AuditEvent}.
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

        /** Builds the event, stamped with the current instant. */
        public AuditEvent build() {
            return new AuditEvent(taskName, category, event, subject, fromState, toState, detail,
                    principal, runId, correlationId, Instant.now());
        }
    }
}
