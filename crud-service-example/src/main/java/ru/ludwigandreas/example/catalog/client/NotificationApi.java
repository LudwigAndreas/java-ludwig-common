package ru.ludwigandreas.example.catalog.client;

import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.PostExchange;
import ru.ludwigandreas.example.catalog.client.dto.NotificationAccepted;
import ru.ludwigandreas.example.catalog.client.dto.SendNotificationRequest;
import ru.ludwigandreas.restclient.annotation.LudwigRestClient;

/**
 * The notification service, as this service calls it.
 *
 * <p>The interface carries the HTTP shape and nothing else. Base URL, timeouts, authentication,
 * retry, circuit breaker, bulkhead, metrics and audit are all properties of the {@code notifications}
 * client under {@code ludwig.rest-client.clients} - deliberately not here, because a call site should
 * not be able to disagree with the deployment about how a dependency is reached.
 *
 * <h2>The two headers, and why they are parameters</h2>
 *
 * <p>Both are per-call decisions that no configuration can make.
 *
 * <p><b>{@code Idempotency-Key}</b> is what makes a retry safe on the far side: the notification
 * service answers a repeat with 200 and the original request instead of fanning it out again. Without
 * it, a read timeout on a request the service did process means the recipient is notified twice.
 *
 * <p><b>{@code X-Ludwig-Retry}</b> is what makes a retry happen on this side. The rest-client
 * pipeline refuses to repeat a POST by default, and that default is correct: a read timeout says the
 * answer did not arrive, never that the peer failed to process the request. Sending {@code true} is
 * this call site asserting the one thing that makes a POST repeatable - that it carries an
 * idempotency key the peer honours. The header is consumed by the pipeline and never leaves the
 * process.
 *
 * <p>Passing them together is not a coincidence, and they should never be separated: opting into
 * retry without a key is exactly the duplicate this pair exists to prevent.
 */
@LudwigRestClient("notifications")
public interface NotificationApi {

    /** Value of {@code X-Ludwig-Retry} that opts a POST into the client's retry policy. */
    String RETRY_OPT_IN = "true";

    /**
     * Submits one notification request.
     *
     * @param request        what to send, to whom
     * @param idempotencyKey stable across every retry of the same logical submission
     * @param retryOptIn     {@link #RETRY_OPT_IN}; see the interface comment
     * @return the accepted request, or the original one when {@code idempotencyKey} matched
     */
    @PostExchange("/api/v1/notifications")
    NotificationAccepted send(@org.springframework.web.bind.annotation.RequestBody
                              SendNotificationRequest request,
                              @RequestHeader("Idempotency-Key") String idempotencyKey,
                              @RequestHeader("X-Ludwig-Retry") String retryOptIn);
}
