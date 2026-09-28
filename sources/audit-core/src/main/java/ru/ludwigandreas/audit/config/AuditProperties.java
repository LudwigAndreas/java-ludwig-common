package ru.ludwigandreas.audit.config;

import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.ludwigandreas.audit.AuditCategories;
import ru.ludwigandreas.audit.AuditFailurePolicy;

/**
 * Configuration for the platform's audit trail.
 *
 * <p>The defaults are chosen so that adding the starter to a service gives it a working, honest trail
 * without a line of configuration: log and database sinks on, outbox sink off, retention measured in
 * years, and the failure policy that fails a settings change whose audit row could not be written.
 *
 * <p>One properties class for the {@code ludwig.audit} prefix, in {@code audit-core} even though the
 * {@code jpa}, {@code outbox}, {@code retention} and {@code liquibase} blocks describe features that only
 * {@code audit-spring-boot-starter} implements. Two {@code @ConfigurationProperties} classes on one prefix
 * is how a property ends up bound in one of them and silently ignored in the other; describing a feature
 * costs this module no dependency at all, and a deployment that sets {@code ludwig.audit.jpa.enabled}
 * without the starter on its classpath gets a property nothing reads rather than a startup failure.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ludwig.audit")
public class AuditProperties {

    /** Whether this module wires anything at all. */
    private boolean enabled = true;

    /**
     * Which deployment is recorded as the writer of each row.
     *
     * <p>Defaults to {@code spring.application.name} when that is set, and to {@code unknown} otherwise
     * - see {@code AuditAutoConfiguration}. It matters because this table can receive events relayed
     * from another service, at which point "which service wrote this row" and "which service the event
     * is about" are different questions.
     */
    private String sourceSystem;

    /** The SLF4J sink, which is the trail an estate with no audit table still has. */
    private final Slf4j slf4j = new Slf4j();

    /** The {@code audit_event} table. */
    private final Jpa jpa = new Jpa();

    /** Shipping the trail off-platform through the transactional outbox. */
    private final Outbox outbox = new Outbox();

    /** What happens to the caller when a sink fails. */
    private final Failure failure = new Failure();

    /** How long events are kept. */
    private final Retention retention = new Retention();

    /** This module's own Liquibase changelog. */
    private final Liquibase liquibase = new Liquibase();

    /** Which values are treated as sensitive, and therefore masked before anything is written. */
    private final Redaction redaction = new Redaction();

    /** The SLF4J sink. */
    @Getter
    @Setter
    public static class Slf4j {

        /** On by default: a deployment whose log pipeline is its audit pipeline needs nothing else. */
        private boolean enabled = true;
    }

    /** The {@code audit_event} table. */
    @Getter
    @Setter
    public static class Jpa {

        /** On by default when a {@code DataSource} is present. */
        private boolean enabled = true;
    }

    /** The outbox sink. */
    @Getter
    @Setter
    public static class Outbox {

        /**
         * Off by default. Shipping a service's whole audit trail to a broker is a data decision taken
         * on the deployment's behalf if it is made here.
         */
        private boolean enabled;

        /**
         * The categories shipped, as an allow-list.
         *
         * <p>Defaults to the two whose events are always emitted inside a transaction. The outbox
         * publisher is {@code Propagation.MANDATORY}, so naming a category whose events are emitted
         * outside one - an authorization denial, an outbound call record - turns every one of those
         * events into an {@code IllegalTransactionStateException}. The allow-list is the guard, and it
         * is an allow-list rather than a deny-list so that a category added later does not start being
         * shipped because nobody thought to exclude it.
         */
        private Set<String> categories =
                new LinkedHashSet<>(List.of(AuditCategories.SETTINGS, AuditCategories.EXPORT));
    }

    /** The failure policy. */
    @Getter
    @Setter
    public static class Failure {

        /**
         * The policy for a category the map below does not name.
         *
         * <p>{@link AuditFailurePolicy#LOG_AND_CONTINUE}, because failing a request because the audit
         * write failed turns an audit outage into a service outage - and the operational response to
         * that is invariably to switch the trail off, which is worse than the missing row.
         */
        private AuditFailurePolicy defaultPolicy = AuditFailurePolicy.LOG_AND_CONTINUE;

        /**
         * Per-category overrides.
         *
         * <p>{@code settings} defaults to {@link AuditFailurePolicy#FAIL_OPERATION}: a settings or
         * consent change that committed without its audit row is precisely the thing the trail exists to
         * make impossible. It is the only category defaulted that way, and the reason is not that its
         * events matter more - it is that its audit write is the one that already runs inside the
         * caller's transaction, so rethrowing actually rolls the change back rather than reporting a
         * change that stands anyway. Adding a category here without moving its audit write into the
         * caller's transaction produces an error on an operation that happened regardless, which is
         * worse than either policy.
         */
        private Map<String, AuditFailurePolicy> byCategory = new LinkedHashMap<>(
                Map.of(AuditCategories.SETTINGS, AuditFailurePolicy.FAIL_OPERATION));
    }

    /** Retention. */
    @Getter
    @Setter
    public static class Retention {

        /** Whether the purge job runs at all. */
        private boolean enabled = true;

        /**
         * How long an event is kept when its category names no period of its own.
         *
         * <p>Seven years, measured in years rather than days on purpose. An audit trail's retention is
         * a compliance figure, and a default measured in days is a default somebody set because it was
         * convenient for disk. A deployment with a shorter legal basis shortens it deliberately; the
         * purge records having done so.
         */
        private Period defaultPeriod = Period.ofYears(7);

        /** Per-category retention, for the categories whose legal basis differs. */
        private Map<String, Period> byCategory = new LinkedHashMap<>();

        /** How often the purge runs. */
        private Duration interval = Duration.ofHours(6);

        /** How many rows one pass removes, so a long backlog is worked through rather than loaded. */
        private int batchSize = 500;

        /** How long shutdown waits for an in-flight purge before leaving it to another instance. */
        private Duration drainTimeout = Duration.ofSeconds(30);
    }

    /** This module's changelog. */
    @Getter
    @Setter
    public static class Liquibase {

        /**
         * Whether to register this module's own {@code SpringLiquibase}.
         *
         * <p>Off switches it so a service can {@code <include>} the changelog in its own master
         * changelog instead, which is what a deployment with a single migration pipeline wants.
         */
        private boolean enabled = true;
    }

    /** Sensitivity classification. */
    @Getter
    @Setter
    public static class Redaction {

        /**
         * Whether the key-name heuristic is applied.
         *
         * <p>On. It is the classifier that catches the secret nobody configured, which is the only kind
         * that ever leaks.
         */
        private boolean keyNameHeuristic = true;

        /**
         * A deployment's own key-name pattern, replacing the shipped one.
         *
         * <p>Note that the pattern is configurable where the mask deliberately is not: widening what
         * counts as sensitive is always safe, whereas a deployment that could change the mask could set
         * it to the empty string, making a redacted value and an absent one the same row.
         */
        private String keyNamePattern;

        /** Provenance prefixes whose values are sensitive whatever they are called. */
        private List<String> sensitiveProvenancePrefixes =
                new ArrayList<>(List.of("vault:", "vault-lease:"));

        /** Additional names - keys, fields, headers - that are always sensitive. */
        private List<String> sensitiveNames = new ArrayList<>();
    }
}
