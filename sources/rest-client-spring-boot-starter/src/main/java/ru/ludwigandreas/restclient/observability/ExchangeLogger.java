package ru.ludwigandreas.restclient.observability;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.restclient.config.LogDetail;
import ru.ludwigandreas.restclient.spi.OutboundRequest;
import ru.ludwigandreas.restclient.spi.OutboundResponse;

/**
 * Writes one client's exchanges to the log, at the detail its configuration asks for.
 *
 * <h2>The logger name is per client</h2>
 *
 * <p>Every line goes to {@code ludwig.restclient.<client>}. That is what makes exchange logging an
 * operational control rather than an all-or-nothing switch: raising {@code billing} to DEBUG during
 * an incident, and leaving the other nine clients alone, is a logging-level change - and with
 * observability-spring-boot-starter present, one that takes effect without a restart.
 *
 * <p>Two independent gates, deliberately. {@code logging.level} decides <em>what</em> is written -
 * nothing, a line, the headers, the body - and is a deployment decision about data. The SLF4J level
 * decides <em>whether</em> it is written and is an operational one. Collapsing them would mean
 * turning on debugging for a client also turns on body logging for it, which is how personal data
 * arrives in a log pipeline at 3am.
 *
 * <h2>Levels</h2>
 *
 * <p>A completed call is logged at INFO and a failed one at WARN. A failure is worth a level that
 * an operator's default filter shows, and it carries the status and the attempt count, which is what
 * turns "billing is slow" into "billing answered 503 three times in 4.2 seconds".
 */
public class ExchangeLogger {

    private final Logger log;
    private final LogDetail level;
    private final boolean logBeforeCall;
    /** Creates a logger writing to {@code ludwig.restclient.<clientName>}. */
    public ExchangeLogger(String clientName, LogDetail level, boolean logBeforeCall) {
        this.log = LoggerFactory.getLogger("ludwig.restclient." + clientName);
        this.level = level;
        this.logBeforeCall = logBeforeCall;
    }

    /** Whether anything at all is logged, so the pipeline can skip building the arguments. */
    public boolean enabled() {
        return level != LogDetail.NONE;
    }

    /** Whether the body is wanted, which is what decides whether the pipeline buffers it. */
    public boolean wantsBody() {
        return level.includes(LogDetail.BODY);
    }

    /**
     * The "about to call" line, written only when {@code logging.log-request-before-call} is on.
     *
     * <p>It doubles the line count and earns that exactly once: when a call hangs, this is the only
     * evidence the call was ever made.
     */
    public void logRequest(OutboundRequest request, String body) {
        if (!logBeforeCall || !enabled() || !log.isInfoEnabled()) {
            return;
        }
        log.info("--> {} {} attempt={}{}{}", request.method(), request.uri(), request.attempt(),
                headersPart(request.headers()), bodyPart(body));
    }

    /** The completed-call line. */
    public void logResponse(OutboundRequest request, OutboundResponse response) {
        if (!enabled()) {
            return;
        }
        if (response.successful()) {
            if (log.isInfoEnabled()) {
                log.info("<-- {} {} {} in {}ms attempt={}{}{}", request.method(), request.uriTemplate(),
                        response.statusCode(), response.duration().toMillis(), request.attempt(),
                        headersPart(response.headers()), bodyPart(response.bodySnippet()));
            }
            return;
        }
        if (log.isWarnEnabled()) {
            log.warn("<-- {} {} {} in {}ms attempt={}{}{}", request.method(), request.uriTemplate(),
                    response.statusCode(), response.duration().toMillis(), request.attempt(),
                    headersPart(response.headers()), bodyPart(response.bodySnippet()));
        }
    }

    /** The line for an attempt that produced no response at all. */
    public void logFailure(OutboundRequest request, Throwable error, Duration duration) {
        if (!enabled() || !log.isWarnEnabled()) {
            return;
        }
        // The exception is passed as the last argument so the stack trace is attached; the message
        // still reads without it, because most readers only see the message.
        log.warn("<-- {} {} failed after {}ms attempt={}: {}", request.method(), request.uriTemplate(),
                duration.toMillis(), request.attempt(), error.toString(), error);
    }

    private String headersPart(Map<String, List<String>> headers) {
        if (!level.includes(LogDetail.HEADERS) || headers.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" headers={");
        headers.forEach((name, values) -> sb.append(name).append(": ")
                .append(String.join(",", values)).append("; "));
        return sb.append('}').toString();
    }

    private String bodyPart(String body) {
        if (!level.includes(LogDetail.BODY) || body == null || body.isEmpty()) {
            return "";
        }
        return " body=" + body;
    }
}
