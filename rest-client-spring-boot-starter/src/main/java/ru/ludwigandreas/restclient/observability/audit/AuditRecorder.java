package ru.ludwigandreas.restclient.observability.audit;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.restclient.config.AuditProperties;
import ru.ludwigandreas.restclient.observability.RestClientMeters;
import ru.ludwigandreas.restclient.spi.AuditEventEmitter;
import ru.ludwigandreas.restclient.spi.OutboundCallAudit;

/**
 * Decides whether a call is audited, assembles the record, and hands it to the emitter.
 *
 * <h2>Sampling that does not lose the interesting calls</h2>
 *
 * <p>{@code audit.sampling-probability} applies to successful calls only. Failures, refused calls and
 * authentication errors are always recorded. Uniform sampling would be simpler and would be wrong:
 * the rare events are the ones an audit exists for, and sampling a 0.1% failure rate at 10% leaves
 * one record in a thousand failures - which is indistinguishable from none when somebody is asking
 * what happened.
 *
 * <h2>What is not in the record</h2>
 *
 * <p>No expanded URI, no request body, no response body, and no header that was not named in the
 * allow-lists. An allow-list rather than a deny-list because an audit store is retained for years:
 * a deny-list is only as good as the last person who remembered to extend it, and the cost of
 * getting it wrong is a credential that has been retained since.
 */
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final String clientName;
    private final boolean enabled;
    private final double samplingProbability;
    private final Set<String> requestHeaders;
    private final Set<String> responseHeaders;
    private final AuditEventEmitter emitter;
    private final RestClientMeters meters;
    private final Clock clock;

    /** Creates the recorder for one named client from its {@code audit} block. */
    // CHECKSTYLE.OFF: ParameterNumber - configuration plus two collaborators, all per client.
    public AuditRecorder(String clientName, AuditProperties properties, AuditEventEmitter emitter,
                         RestClientMeters meters, Clock clock) {
        this.clientName = clientName;
        this.enabled = Boolean.TRUE.equals(properties.getEnabled());
        this.samplingProbability = properties.getSamplingProbability();
        this.requestHeaders = lowerCased(properties.getIncludeRequestHeaders());
        this.responseHeaders = lowerCased(properties.getIncludeResponseHeaders());
        this.emitter = emitter;
        this.meters = meters;
        this.clock = clock;
    }
    // CHECKSTYLE.ON: ParameterNumber

    /** Whether auditing is on for this client at all. */
    public boolean enabled() {
        return enabled;
    }

    /** Assembles and emits the record for one completed logical call. */
    public void record(AuditRecord record) {
        if (!enabled || !shouldRecord(record)) {
            return;
        }
        OutboundCallAudit audit = new OutboundCallAudit(
                clientName,
                record.method(),
                record.uriTemplate(),
                record.statusCode(),
                record.outcome(),
                record.duration(),
                record.attempts(),
                record.correlationId(),
                record.traceId(),
                record.principal(),
                clock.instant().minus(record.duration()),
                allowedHeaders(record),
                record.failureType());
        emit(audit);
    }

    private void emit(OutboundCallAudit audit) {
        try {
            emitter.emit(audit);
        } catch (RuntimeException ex) {
            // Same rule as a listener: an audit sink must not be able to fail a business call. The
            // counter is what makes a sink that has been silently dropping records for a month
            // visible before an auditor finds it.
            meters.auditFailure(clientName);
            log.warn("Audit emitter {} failed for client {}; the call was not affected.",
                    emitter.getClass().getName(), clientName, ex);
        }
    }

    private boolean shouldRecord(AuditRecord record) {
        if (!"SUCCESS".equals(record.outcome())) {
            return true;
        }
        if (samplingProbability >= 1.0) {
            return true;
        }
        return samplingProbability > 0.0
                && ThreadLocalRandom.current().nextDouble() < samplingProbability;
    }

    private Map<String, List<String>> allowedHeaders(AuditRecord record) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        copyAllowed(record.requestHeaders(), requestHeaders, "req.", out);
        copyAllowed(record.responseHeaders(), responseHeaders, "res.", out);
        return out;
    }

    private void copyAllowed(Map<String, List<String>> source, Set<String> allowed, String prefix,
                             Map<String, List<String>> out) {
        if (source == null || allowed.isEmpty()) {
            return;
        }
        source.forEach((name, values) -> {
            if (allowed.contains(name.toLowerCase(Locale.ROOT))) {
                out.put(prefix + name, values);
            }
        });
    }

    private static Set<String> lowerCased(List<String> values) {
        Set<String> out = new TreeSet<>();
        if (values != null) {
            values.forEach(value -> out.add(value.toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    /**
     * What the pipeline hands the recorder about a finished call.
     *
     * <p>A separate type from {@link OutboundCallAudit} because it carries the <em>unfiltered</em>
     * headers: the recorder is what applies the allow-list, and giving it a type that is already
     * filtered would put that decision in the pipeline, where it would eventually be made twice.
     *
     * @param method          the HTTP method
     * @param uriTemplate     the URI template
     * @param statusCode      the final status, or 0
     * @param outcome         the outcome label
     * @param duration        total wall time of the logical call
     * @param attempts        attempts made
     * @param correlationId   the platform correlation id
     * @param traceId         the W3C trace id, when sampled
     * @param principal       the authenticated subject, or {@code null}
     * @param requestHeaders  all request headers, already redacted
     * @param responseHeaders all response headers, already redacted
     * @param failureType     the exception class name, or {@code null}
     */
    // CHECKSTYLE.OFF: ParameterNumber - a record of one call; every component is one fact about it.
    public record AuditRecord(
            String method,
            String uriTemplate,
            int statusCode,
            String outcome,
            Duration duration,
            int attempts,
            String correlationId,
            String traceId,
            String principal,
            Map<String, List<String>> requestHeaders,
            Map<String, List<String>> responseHeaders,
            String failureType) {
    }
    // CHECKSTYLE.ON: ParameterNumber
}
