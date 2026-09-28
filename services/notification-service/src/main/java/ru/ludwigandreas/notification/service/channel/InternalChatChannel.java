package ru.ludwigandreas.notification.service.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.service.Pii;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.DeliveryResult;
import ru.ludwigandreas.notification.service.model.FailureClass;
import ru.ludwigandreas.notification.service.model.RenderedNotification;

/**
 * Posts to the internal chat system's HTTP API.
 *
 * <p>Treated as a generic outbound HTTP integration rather than as a special case, because that is
 * what it is: a URL, a bearer token, a JSON body and a status code. Writing it against a vendor SDK
 * would make the chat channel the one that behaves differently when the provider is slow - different
 * timeouts, different retry semantics, a different idea of what a 429 means - which is exactly the
 * divergence the shared {@link HttpFailureClassifier} exists to prevent.
 *
 * <p>The correlation id is forwarded as a header, so the chat system's own logs join to ours for a
 * single business operation. That is the point of the brief's requirement that the id reach the outbound
 * provider call: a notification that "was sent" according to us and never appeared according to them
 * is otherwise two unrelated log searches.
 */
@Slf4j
public class InternalChatChannel implements NotificationChannel {

    private static final String SEND_PATH = "/api/v1/messages";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    /** Field the provider returns its own message id under; the key a receipt is later matched on. */
    private static final String MESSAGE_ID_FIELD = "messageId";

    /** How much of an error body is kept in {@code last_error}; enough to diagnose, not a dump. */
    private static final int MAX_ERROR_DETAIL = 512;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final NotificationProperties.Chat settings;

    public InternalChatChannel(RestClient restClient, ObjectMapper objectMapper,
                               NotificationProperties.Chat settings) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.settings = settings;
    }

    @Override
    public boolean supports(ChannelType channelType) {
        return channelType == ChannelType.CHAT;
    }

    @Override
    public String name() {
        return "internal-chat";
    }

    @Override
    public DeliveryResult send(RenderedNotification notification) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recipient", notification.address());
        body.put("title", notification.subject());
        body.put("body", notification.htmlBody());
        body.put("plainBody", notification.textBody());
        body.put("category", notification.category());
        body.put("locale", notification.recipientLocale());
        // The provider's own idempotency key. Sending it is what makes a retry after an ambiguous
        // timeout safe: the provider recognises the repeat instead of posting the message twice.
        body.put("idempotencyKey", notification.deliveryId().toString());
        body.putAll(notification.headers());

        ResponseEntity<String> response = restClient.post()
                .uri(SEND_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(CORRELATION_HEADER, notification.correlationId())
                .headers(headers -> {
                    if (settings.getApiToken() != null && !settings.getApiToken().isBlank()) {
                        headers.setBearerAuth(settings.getApiToken());
                    }
                })
                .body(body)
                .retrieve()
                // Default error handling is switched off so a 4xx/5xx becomes a classified
                // DeliveryResult rather than an exception. A thrown status would be caught by the
                // dispatcher's blanket handler and classified as retryable, which would turn every
                // permanent rejection into eight attempts.
                .onStatus(HttpStatusCode::isError, (request, failed) -> { })
                .toEntity(String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            return DeliveryResult.sent(messageIdOf(response.getBody(), notification));
        }

        FailureClass failureClass = HttpFailureClassifier.classify(response.getStatusCode());
        String detail = "chat API returned " + response.getStatusCode().value() + ": "
                + truncate(response.getBody());
        return failureClass == FailureClass.RETRYABLE
                ? DeliveryResult.retryable(detail)
                : DeliveryResult.terminal(detail);
    }

    private String messageIdOf(String body, RenderedNotification notification) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode messageId = node.path(MESSAGE_ID_FIELD);
            return messageId.isMissingNode() || messageId.isNull() ? null : messageId.asText();
        } catch (com.fasterxml.jackson.core.JacksonException e) {
            // The message was accepted; only our ability to correlate a later receipt is lost. That
            // is worth a log line and is emphatically not worth failing a successful send over.
            log.warn("Chat API accepted delivery {} to {} but returned an unreadable body",
                    notification.deliveryId(), Pii.address(notification.address()), e);
            return null;
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return "<no body>";
        }
        return body.length() <= MAX_ERROR_DETAIL ? body : body.substring(0, MAX_ERROR_DETAIL) + "...";
    }
}
