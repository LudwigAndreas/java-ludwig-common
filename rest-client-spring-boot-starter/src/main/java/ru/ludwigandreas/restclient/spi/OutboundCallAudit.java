package ru.ludwigandreas.restclient.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One retained record of an outbound call.
 *
 * <p>Everything in it is either non-identifying or deliberately chosen: there is no raw URI, no
 * request body, no response body and no header that was not named in
 * {@code audit.include-request-headers} / {@code audit.include-response-headers}. That is not
 * caution for its own sake - an audit store is retained for years and read by people who are not
 * on the on-call rota, and the cheapest way to never leak a token into it is to have no field that
 * could carry one.
 *
 * @param clientName    the named client
 * @param method        the HTTP method
 * @param uriTemplate   the URI template, never the expanded path
 * @param statusCode    the HTTP status, or 0 when no response was received
 * @param outcome       {@code SUCCESS}, {@code CLIENT_ERROR}, {@code SERVER_ERROR},
 *                      {@code TIMEOUT}, {@code CONNECTION_ERROR}, {@code AUTH_ERROR},
 *                      {@code NOT_PERMITTED} or {@code UNKNOWN}
 * @param duration      wall time of the whole logical call, retries included
 * @param attempts      attempts made
 * @param correlationId the platform correlation id
 * @param traceId       the W3C trace id, when a trace was sampled
 * @param principal     who caused the call - the authenticated subject, or {@code null} for work
 *                      with no user behind it
 * @param at            when the call started
 * @param headers       the allow-listed headers, request and response, prefixed {@code req.}/
 *                      {@code res.}
 * @param failureType   the exception class name when the call ended in one
 */
public record OutboundCallAudit(
        String clientName,
        String method,
        String uriTemplate,
        int statusCode,
        String outcome,
        Duration duration,
        int attempts,
        String correlationId,
        String traceId,
        String principal,
        Instant at,
        Map<String, List<String>> headers,
        String failureType) {

    public OutboundCallAudit {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
