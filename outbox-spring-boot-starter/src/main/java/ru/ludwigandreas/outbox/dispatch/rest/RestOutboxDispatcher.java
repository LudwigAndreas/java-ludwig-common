package ru.ludwigandreas.outbox.dispatch.rest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import ru.ludwigandreas.outbox.dispatch.DispatchResult;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcher;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.outbox.exception.OutboxDispatchException;

import java.util.Map;

/**
 * {@code destination} is either a full URL or a logical key looked up in {@code ludwig.outbox.rest.endpoints}.
 * A 4xx response (except 429, treated as backpressure) is non-retryable and sends the message straight
 * to DEAD_LETTER; anything else (5xx, timeouts, connection failures) is retried with backoff.
 */
public class RestOutboxDispatcher implements OutboxDispatcher {

    public static final String TRANSPORT = "REST";

    private final RestClient restClient;
    private final Map<String, String> endpoints;

    public RestOutboxDispatcher(RestClient restClient, Map<String, String> endpoints) {
        this.restClient = restClient;
        this.endpoints = endpoints;
    }

    @Override
    public String transport() {
        return TRANSPORT;
    }

    @Override
    public DispatchResult dispatch(OutboxMessage message) {
        String url = resolveUrl(message.getDestination());
        try {
            restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> applyHeaders(headers, message))
                    .body(message.getPayload())
                    .retrieve()
                    .toBodilessEntity();
            return DispatchResult.success();
        } catch (HttpStatusCodeException e) {
            HttpStatusCode status = e.getStatusCode();
            boolean retryable = !status.is4xxClientError() || status.value() == 429;
            return DispatchResult.failure("HTTP " + status.value() + ": " + e.getMessage(), retryable);
        } catch (RestClientException e) {
            return DispatchResult.failure(e.getMessage(), true);
        }
    }

    private String resolveUrl(String destination) {
        if (destination.startsWith("http://") || destination.startsWith("https://")) {
            return destination;
        }
        String resolved = endpoints.get(destination);
        if (resolved == null) {
            throw new OutboxDispatchException(
                    "No REST endpoint configured under ludwig.outbox.rest.endpoints for destination '" + destination + "'");
        }
        return resolved;
    }

    private static void applyHeaders(HttpHeaders headers, OutboxMessage message) {
        headers.add("X-Event-Type", message.getEventType());
        headers.add("X-Event-Version", String.valueOf(message.getEventVersion()));
        if (message.getIdempotencyKey() != null) {
            headers.add("X-Idempotency-Key", message.getIdempotencyKey());
        }
        if (message.getTraceId() != null) {
            headers.add("X-Trace-Id", message.getTraceId());
        }
        Map<String, String> customHeaders = message.getHeaders();
        if (customHeaders != null) {
            customHeaders.forEach((key, value) -> headers.add("X-Outbox-" + key, value));
        }
    }
}
