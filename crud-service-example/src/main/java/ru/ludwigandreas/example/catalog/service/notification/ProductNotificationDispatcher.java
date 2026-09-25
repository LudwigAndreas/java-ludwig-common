package ru.ludwigandreas.example.catalog.service.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.example.catalog.client.NotificationApi;
import ru.ludwigandreas.example.catalog.client.dto.NotificationAccepted;
import ru.ludwigandreas.example.catalog.client.dto.NotificationRecipient;
import ru.ludwigandreas.example.catalog.client.dto.SendNotificationRequest;
import ru.ludwigandreas.example.catalog.service.event.ProductEventPayload;
import ru.ludwigandreas.outbox.dispatch.DispatchResult;
import ru.ludwigandreas.outbox.dispatch.OutboxDispatcher;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.restclient.error.RestClientAuthenticationException;
import ru.ludwigandreas.restclient.error.RestClientCallNotPermittedException;
import ru.ludwigandreas.restclient.error.RestClientResponseException;

/**
 * Turns a committed product change into a call to the notification service.
 *
 * <h2>Why this is an outbox dispatcher and not a method call in the service</h2>
 *
 * <p>Calling the notification service from inside {@code ProductServiceImpl.create} would put a
 * network call inside a database transaction, and every possible ordering of that is wrong. Call
 * before the commit and a rolled-back product has already been announced. Call after it and a crash
 * in between loses the notification with nothing to say so. Call inside it and the transaction - and
 * the row locks it holds - stays open for as long as the peer takes to answer, which on a bad day is
 * the read timeout.
 *
 * <p>The outbox removes the choice. The intent is written in the same transaction as the product, so
 * it commits if and only if the product does, and a background poller makes the call afterwards with
 * retry, backoff and dead-lettering it already has. What is left here is the mapping and the decision
 * about which failures are worth trying again.
 *
 * <h2>Two layers of retry, doing different jobs</h2>
 *
 * <p>The {@code notifications} REST client retries inside one call - a connect timeout, a 503 - over
 * a second or two, because a dependency that is briefly unavailable should not cost a round trip
 * through the database. The outbox retries across minutes and process restarts, because a dependency
 * that is properly down should not be hammered and must not be forgotten. Neither replaces the other,
 * and this method is where the boundary is drawn: whatever the client gives up on, this classifies.
 */
@Slf4j
@RequiredArgsConstructor
public class ProductNotificationDispatcher implements OutboxDispatcher {

    /**
     * The transport name this dispatcher answers to, matched case-insensitively against
     * {@code ludwig.outbox.routes.*.transport}.
     */
    public static final String TRANSPORT = "NOTIFICATIONS";

    private final NotificationApi notifications;
    private final StewardNotificationSettings settings;
    private final ObjectMapper objectMapper;

    @Override
    public String transport() {
        return TRANSPORT;
    }

    @Override
    public DispatchResult dispatch(OutboxMessage message) {
        ProductEventPayload product;
        try {
            product = objectMapper.readValue(message.getPayload(), ProductEventPayload.class);
        } catch (JsonProcessingException e) {
            // The row's payload cannot be read as the type this service wrote, which means a
            // deployment changed that type while rows were in flight. Retrying cannot fix it, so the
            // message goes straight to DEAD_LETTER where a human will find it.
            log.error("Outbox message {} does not deserialize as a product event", message.getId(), e);
            return DispatchResult.failure("payload is not a ProductEventPayload: " + e.getMessage(), false);
        }

        try {
            NotificationAccepted accepted = notifications.send(
                    toRequest(product), idempotencyKeyOf(message), NotificationApi.RETRY_OPT_IN);
            log.info("Notification request {} accepted for product {}{}",
                    accepted.id(), product.id(), accepted.duplicate() ? " (duplicate)" : "");
            return DispatchResult.success();
        } catch (RestClientResponseException e) {
            return classify(message, e);
        } catch (RestClientAuthenticationException e) {
            // No token could be minted. That is this deployment's configuration or the authorization
            // server, not the message - it will affect every message equally, and it will be fixed by
            // somebody rather than by time, so the row waits rather than burning its budget.
            log.error("Could not authenticate to the notification service", e);
            return DispatchResult.failure("authentication failed: " + e.getMessage(), true);
        } catch (RestClientCallNotPermittedException e) {
            // The circuit breaker is open: the dependency is known to be failing and the call was not
            // made at all. Retryable by definition, and cheap - no request left this process.
            log.warn("Notification service circuit is open; message {} will be retried", message.getId());
            return DispatchResult.failure("circuit breaker open", true);
        }
    }

    /**
     * Decides whether a status is worth another attempt.
     *
     * <p>A 4xx means this service sent something the notification service rejected, and sending it
     * again unchanged will be rejected again - eight more times, then dead-lettered anyway, having
     * spent an hour pretending there was hope. Straight to the dead-letter queue instead, where the
     * body snippet on the exception says what was wrong.
     *
     * <p>The two exceptions are the 4xx codes that are about timing rather than content: 408 and 429
     * both mean "not now", and 429 in particular is the peer asking to be called later.
     */
    private DispatchResult classify(OutboxMessage message, RestClientResponseException failure) {
        boolean retryable = !failure.clientError()
                || failure.getStatusCode() == RETRY_AFTER_TIMEOUT
                || failure.getStatusCode() == TOO_MANY_REQUESTS;
        if (retryable) {
            log.warn("Notification service answered {} for message {}; retrying",
                    failure.getStatusCode(), message.getId());
        } else {
            log.error("Notification service rejected message {} with {}; dead-lettering",
                    message.getId(), failure.getStatusCode(), failure);
        }
        return DispatchResult.failure("HTTP " + failure.getStatusCode() + ": " + failure.getMessage(), retryable);
    }

    private SendNotificationRequest toRequest(ProductEventPayload product) {
        List<NotificationRecipient> recipients = settings.getStewardUserIds().stream()
                .map(NotificationRecipient::ofUser)
                .toList();
        // The variable map is the template's whole input. Only what the copy needs goes in it: a
        // notification service holding a product's full record would be a second, stale catalogue.
        Map<String, Object> variables = Map.of(
                "productId", product.id().toString(),
                "sku", product.sku(),
                "name", product.name(),
                "price", product.price(),
                "categoryCode", product.categoryCode());

        return new SendNotificationRequest(
                settings.getTemplateKey(),
                settings.getCategory(),
                settings.getCategoryClass(),
                settings.getPriority(),
                Set.copyOf(settings.getChannels()),
                recipients,
                variables);
    }

    /**
     * The key that makes a retry safe on the far side.
     *
     * <p>The outbox row's own idempotency key when it has one, because that key is already stable
     * across every retry of this message and across a re-run of the transaction that produced it.
     * Falling back to the row id keeps the guarantee for a message published without one: the id is
     * generated once, at publish time, and every dispatch attempt of that row reuses it.
     *
     * <p>What must never be used here is anything generated per attempt. A fresh key on each retry
     * makes the far side's deduplication useless in exactly the case it exists for.
     */
    private static String idempotencyKeyOf(OutboxMessage message) {
        return message.getIdempotencyKey() != null
                ? message.getIdempotencyKey()
                : message.getId().toString();
    }

    /** 408 Request Timeout - the peer gave up waiting, which says nothing about the request's content. */
    private static final int RETRY_AFTER_TIMEOUT = 408;

    /** 429 Too Many Requests - the peer asking to be called later. */
    private static final int TOO_MANY_REQUESTS = 429;
}
