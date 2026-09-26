package ru.ludwigandreas.restclient.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/**
 * Audit of outbound calls for one named client.
 *
 * <p>Distinct from logging, and not a louder version of it. An audit record answers "who caused this
 * service to call that partner, when, and what came back" - it is retained, it is read by people who
 * are not operators, and it must never contain a credential or a payload. Logging answers "what is
 * this process doing right now" and is discarded in days.
 *
 * <p>These settings stay here, per named client, after the audit consolidation: whether a partner's calls
 * are audited at all, at what sampling rate, and which of its headers are allow-listed are facts about the
 * relationship with that partner, not about auditing in general. What moved to {@code ludwig.audit} is the
 * destination, the failure policy and the retention.
 */
@Getter
@Setter
public class AuditProperties {

    /**
     * Built-in default: false. Auditing every outbound call by default would be a data decision
     * taken on a service's behalf.
     */
    private Boolean enabled;

    /**
     * Fraction of calls recorded, 0.0 to 1.0. Built-in default: 1.0.
     *
     * <p>Sampling applies to successful calls only. A failure, a denied call and an authentication
     * error are always recorded regardless of this value: the rare events are the ones an audit
     * exists for, and sampling them away defeats the record.
     */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private Double samplingProbability;

    /**
     * Bean name of an {@code AuditSink} for this client alone. Absent uses the platform's sink.
     *
     * <p>Replaces {@code audit.emitter}, which named an {@code AuditEventEmitter}: that SPI and its
     * logging implementation are gone into {@code audit-core}, and this module's trail now goes wherever
     * the rest of the platform's does - {@code Slf4jAuditSink}, the {@code audit_event} table, a SIEM
     * through the outbox, or several at once. Per client rather than only globally because one service can
     * legitimately have to send one partner's trail to a vendor's API and the rest to the platform's sink.
     *
     * <p>A deployment still setting {@code audit.emitter} will find it bound to nothing. That is a
     * deliberate rename rather than a silent alias: an alias would let a property that names a bean of a
     * type no longer on the classpath look like it was honoured.
     */
    private String sink;

    /**
     * Response header names whose value is recorded. Built-in default: empty.
     *
     * <p>An allow-list rather than a deny-list: an audit record that accidentally includes
     * {@code Set-Cookie} because nobody thought to exclude it is a retained credential.
     */
    private List<String> includeResponseHeaders;

    /** Request header names whose value is recorded. Same allow-list reasoning. */
    private List<String> includeRequestHeaders;
}
