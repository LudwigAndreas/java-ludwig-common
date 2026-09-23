package ru.ludwigandreas.restclient.core;

import ru.ludwigandreas.restclient.config.ClientProperties;
import ru.ludwigandreas.restclient.observability.ExchangeLogger;
import ru.ludwigandreas.restclient.observability.HeaderRedactor;
import ru.ludwigandreas.restclient.observability.audit.AuditRecorder;
import ru.ludwigandreas.restclient.resilience.RetryPolicy;

/**
 * The part of a client that may change without a restart.
 *
 * <p>Everything here is derived from properties and from nothing else: no socket, no pool, no
 * {@code SSLContext}, no token cache, no circuit-breaker window. That is exactly the line between
 * what can be swapped under a running client and what cannot - changing a value that is baked into a
 * long-lived object would leave some calls on the old object and some on the new one, and the two
 * would disagree about how the dependency is reached.
 *
 * @param properties     the merged configuration these were derived from
 * @param retry          the retry decision; attempts, backoff, budget and status list are all here
 * @param exchangeLogger the per-client logger, carrying its detail level
 * @param redactor       the header and body redaction lists
 * @param audit          the audit recorder, carrying its sampling probability
 */
public record RefreshableClientState(
        ClientProperties properties,
        RetryPolicy retry,
        ExchangeLogger exchangeLogger,
        HeaderRedactor redactor,
        AuditRecorder audit) {
}
