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
     * Bean name of an {@code AuditEventEmitter}. Absent uses the built-in emitter, which writes one
     * structured line to the {@code ludwig.restclient.audit} logger.
     */
    private String emitter;

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
