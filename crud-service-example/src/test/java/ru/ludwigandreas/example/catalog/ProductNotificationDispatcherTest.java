package ru.ludwigandreas.example.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.example.catalog.client.NotificationApi;
import ru.ludwigandreas.example.catalog.client.dto.NotificationAccepted;
import ru.ludwigandreas.example.catalog.client.dto.NotificationCategoryClass;
import ru.ludwigandreas.example.catalog.client.dto.NotificationChannel;
import ru.ludwigandreas.example.catalog.client.dto.NotificationPriority;
import ru.ludwigandreas.example.catalog.client.dto.NotificationRecipient;
import ru.ludwigandreas.example.catalog.client.dto.SendNotificationRequest;
import ru.ludwigandreas.example.catalog.service.event.ProductEventPayload;
import ru.ludwigandreas.example.catalog.config.CatalogNotificationProperties;
import ru.ludwigandreas.example.catalog.service.notification.ProductNotificationDispatcher;
import ru.ludwigandreas.outbox.dispatch.DispatchResult;
import ru.ludwigandreas.outbox.entity.OutboxMessage;
import ru.ludwigandreas.restclient.error.RestClientAuthenticationException;
import ru.ludwigandreas.restclient.error.RestClientCallNotPermittedException;
import ru.ludwigandreas.restclient.error.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping, and the decision that actually matters: which failures are worth another attempt.
 *
 * <p>Getting that classification wrong is expensive in both directions. Marking a 422 retryable
 * spends the whole budget re-sending a body the peer will never accept, and delays the dead-letter
 * that would have told somebody; marking a 503 permanent throws work away during an outage that ends
 * by itself.
 */
class ProductNotificationDispatcherTest {

    private static final String STEWARD = "user-7";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecordingNotificationApi api = new RecordingNotificationApi();
    private final CatalogNotificationProperties properties = properties();
    private final ProductNotificationDispatcher dispatcher =
            new ProductNotificationDispatcher(api, properties, objectMapper);

    @Test
    void answersTheTransportNameTheRouteIsConfiguredWith() {
        assertThat(dispatcher.transport()).isEqualTo("NOTIFICATIONS");
    }

    @Test
    void mapsAProductEventOntoTheNotificationContract() {
        dispatcher.dispatch(message(payload(), "prod-1:NotifyProductCreated:0"));

        SendNotificationRequest sent = api.lastRequest.get();
        assertThat(sent.templateKey()).isEqualTo("catalog-product-published");
        assertThat(sent.category()).isEqualTo("catalog");
        assertThat(sent.categoryClass()).isEqualTo(NotificationCategoryClass.MARKETING);
        assertThat(sent.priority()).isEqualTo(NotificationPriority.BULK);
        assertThat(sent.channels()).containsExactly(NotificationChannel.EMAIL);
        assertThat(sent.recipients()).containsExactly(NotificationRecipient.ofUser(STEWARD));
        assertThat(sent.variables())
                .containsEntry("sku", "SKU-1")
                .containsEntry("name", "Widget")
                .containsEntry("categoryCode", "TOOLS");
    }

    /**
     * The key must be the outbox row's own, because that one is stable across every retry of this
     * message. A per-attempt key would make the far side's deduplication useless in exactly the case
     * it exists for.
     */
    @Test
    void sendsTheOutboxRowsIdempotencyKeySoARetryIsSafeOnTheFarSide() {
        dispatcher.dispatch(message(payload(), "prod-1:NotifyProductCreated:0"));

        assertThat(api.lastIdempotencyKey.get()).isEqualTo("prod-1:NotifyProductCreated:0");
    }

    @Test
    void fallsBackToTheRowIdWhenTheMessageCarriesNoIdempotencyKey() {
        OutboxMessage message = message(payload(), null);

        dispatcher.dispatch(message);

        assertThat(api.lastIdempotencyKey.get()).isEqualTo(message.getId().toString());
    }

    /**
     * Opting a POST into retry is only defensible together with the key above; the two are sent as a
     * pair and a change that drops one should fail here.
     */
    @Test
    void optsThePostIntoRetryBecauseItCarriesAnIdempotencyKey() {
        dispatcher.dispatch(message(payload(), "k"));

        assertThat(api.lastRetryOptIn.get()).isEqualTo(NotificationApi.RETRY_OPT_IN);
    }

    @Test
    void anAcceptedRequestSucceeds() {
        assertThat(dispatcher.dispatch(message(payload(), "k"))).isInstanceOf(DispatchResult.Success.class);
    }

    @Test
    void aRejectedBodyIsDeadLetteredRatherThanRetried() {
        api.failWith = response(422, "Unprocessable Entity", "{\"detail\":\"unknown template\"}");

        DispatchResult result = dispatcher.dispatch(message(payload(), "k"));

        assertThat(result).isInstanceOfSatisfying(DispatchResult.Failure.class, failure -> {
            assertThat(failure.retryable()).isFalse();
            assertThat(failure.reason()).contains("422");
        });
    }

    @Test
    void aFailingPeerIsRetried() {
        api.failWith = response(503, "Service Unavailable", null);

        assertThat(dispatcher.dispatch(message(payload(), "k")))
                .isInstanceOfSatisfying(DispatchResult.Failure.class,
                        failure -> assertThat(failure.retryable()).isTrue());
    }

    /** 429 is the peer asking to be called later - the one 4xx that is about timing, not content. */
    @Test
    void aRateLimitedCallIsRetriedEvenThoughItIsA4xx() {
        api.failWith = response(429, "Too Many Requests", null);

        assertThat(dispatcher.dispatch(message(payload(), "k")))
                .isInstanceOfSatisfying(DispatchResult.Failure.class,
                        failure -> assertThat(failure.retryable()).isTrue());
    }

    @Test
    void aRequestTimeoutIsRetriedEvenThoughItIsA4xx() {
        api.failWith = response(408, "Request Timeout", null);

        assertThat(dispatcher.dispatch(message(payload(), "k")))
                .isInstanceOfSatisfying(DispatchResult.Failure.class,
                        failure -> assertThat(failure.retryable()).isTrue());
    }

    /**
     * A token that cannot be minted affects every message equally and is fixed by a person, not by
     * time - so the row waits rather than burning its budget against a misconfiguration.
     */
    @Test
    void aTokenThatCannotBeMintedIsRetried() {
        api.failWith = new RestClientAuthenticationException(
                "notifications", "corr", "no token", "oauth2-client-credentials", false, null);

        assertThat(dispatcher.dispatch(message(payload(), "k")))
                .isInstanceOfSatisfying(DispatchResult.Failure.class,
                        failure -> assertThat(failure.retryable()).isTrue());
    }

    @Test
    void anOpenCircuitIsRetriedAndCostsNoRequest() {
        api.failWith = new RestClientCallNotPermittedException(
                "notifications", "corr", "open", "circuit-breaker", null);

        assertThat(dispatcher.dispatch(message(payload(), "k")))
                .isInstanceOfSatisfying(DispatchResult.Failure.class,
                        failure -> assertThat(failure.retryable()).isTrue());
        assertThat(api.lastRequest.get()).isNull();
    }

    /**
     * A payload that no longer parses means a deployment changed the event type while rows were in
     * flight. No number of retries fixes that, so it goes straight to a human.
     */
    @Test
    void anUnreadablePayloadIsDeadLetteredWithoutCallingThePeer() {
        OutboxMessage message = message("{\"id\":\"not-a-uuid\"}", "k");

        DispatchResult result = dispatcher.dispatch(message);

        assertThat(result).isInstanceOfSatisfying(DispatchResult.Failure.class,
                failure -> assertThat(failure.retryable()).isFalse());
        assertThat(api.lastRequest.get()).isNull();
    }

    private static RestClientResponseException response(int status, String reason, String body) {
        return new RestClientResponseException("notifications", "corr",
                reason, status, reason, java.util.Map.of(), body, null);
    }

    private static CatalogNotificationProperties properties() {
        CatalogNotificationProperties properties = new CatalogNotificationProperties();
        properties.setEnabled(true);
        properties.setStewardUserIds(List.of(STEWARD));
        return properties;
    }

    private String payload() {
        try {
            return objectMapper.writeValueAsString(new ProductEventPayload(
                    UUID.randomUUID(), "SKU-1", "Widget", new BigDecimal("9.99"), "ACTIVE", "TOOLS"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static OutboxMessage message(String payload, String idempotencyKey) {
        OutboxMessage message = new OutboxMessage();
        message.setId(UUID.randomUUID());
        message.setAggregateType("Product");
        message.setAggregateId("prod-1");
        message.setEventType("NotifyProductCreated");
        message.setTransport(ProductNotificationDispatcher.TRANSPORT);
        message.setDestination("notification-service/api/v1/notifications");
        message.setPayload(payload);
        message.setIdempotencyKey(idempotencyKey);
        return message;
    }

    /** Records what the dispatcher sent, and fails on demand. */
    private static final class RecordingNotificationApi implements NotificationApi {

        private final AtomicReference<SendNotificationRequest> lastRequest = new AtomicReference<>();
        private final AtomicReference<String> lastIdempotencyKey = new AtomicReference<>();
        private final AtomicReference<String> lastRetryOptIn = new AtomicReference<>();
        private final List<SendNotificationRequest> all = new ArrayList<>();

        private RuntimeException failWith;

        @Override
        public NotificationAccepted send(SendNotificationRequest request, String idempotencyKey,
                                         String retryOptIn) {
            if (failWith != null) {
                throw failWith;
            }
            lastRequest.set(request);
            lastIdempotencyKey.set(idempotencyKey);
            lastRetryOptIn.set(retryOptIn);
            all.add(request);
            return new NotificationAccepted(UUID.randomUUID(), "ACCEPTED", false);
        }
    }
}
