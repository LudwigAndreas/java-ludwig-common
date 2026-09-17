package ru.ludwigandreas.notification.service.channel;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.DeliveryResult;
import ru.ludwigandreas.notification.service.model.FailureClass;
import ru.ludwigandreas.notification.service.model.RenderedNotification;

/**
 * Delivers to a URL the recipient registered, signed so the receiver can prove it came from us.
 *
 * <h2>Why it is signed</h2>
 *
 * <p>A webhook endpoint is on the public internet and anybody can post to it. Without a signature the
 * receiver's only options are to trust whatever arrives - which makes it trivially forgeable - or to
 * IP-allowlist us, which breaks the first time this service is rescheduled. An HMAC over
 * {@code <timestamp>.<body>} lets the receiver verify origin and integrity with a shared secret, and
 * the timestamp bounds how long a captured request stays replayable. See {@link HmacSigner}.
 *
 * <h2>Why the URL is validated before the call</h2>
 *
 * <p>The destination comes from a recipient profile, which is data, and data can say anything. A
 * {@code file://} URL or a bare path would be a request this service makes on somebody else's
 * instruction to a target it did not choose - so the scheme is checked first and anything but HTTP or
 * HTTPS is a terminal failure rather than an attempted call.
 */
@Slf4j
public class WebhookChannel implements NotificationChannel {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String DELIVERY_HEADER = "X-Ludwig-Delivery-Id";
    private static final String EVENT_HEADER = "X-Ludwig-Event";

    private static final int MAX_ERROR_DETAIL = 512;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final NotificationProperties.Webhook settings;

    public WebhookChannel(RestClient restClient, ObjectMapper objectMapper,
                          NotificationProperties.Webhook settings) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.settings = settings;
    }

    @Override
    public boolean supports(ChannelType channelType) {
        return channelType == ChannelType.WEBHOOK;
    }

    @Override
    public String name() {
        return "signed-webhook";
    }

    @Override
    public DeliveryResult send(RenderedNotification notification) {
        URI target;
        try {
            target = validTarget(notification.address());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return DeliveryResult.terminal("Webhook URL is not a usable HTTP(S) endpoint: " + e.getMessage());
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(body(notification));
        } catch (JacksonException e) {
            // The body is built from strings this service produced, so a failure here is a bug rather
            // than bad input - and retrying a bug wastes the delivery's whole retry budget.
            return DeliveryResult.terminal("Webhook payload could not be serialized: " + e.getMessage());
        }

        long timestamp = Instant.now().getEpochSecond();
        String signature = HmacSigner.sign(settings.getSigningSecret(), timestamp, payload);

        ResponseEntity<String> response = restClient.post()
                .uri(target)
                .contentType(MediaType.APPLICATION_JSON)
                .header(settings.getSignatureHeader(), signature)
                .header(settings.getTimestampHeader(), Long.toString(timestamp))
                .header(CORRELATION_HEADER, notification.correlationId())
                .header(DELIVERY_HEADER, notification.deliveryId().toString())
                .header(EVENT_HEADER, notification.templateKey())
                // The signature covers exactly these bytes, so the body is sent as a pre-serialized
                // string rather than as an object. Letting the client re-serialize would produce a
                // different byte sequence - a reordered field, a different number format - and every
                // signature would fail verification at the receiver.
                .body(payload)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, failed) -> { })
                .toEntity(String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            // The receiver issues no id of its own, so the delivery id is the correlation handle a
            // receipt would use - and unlike the SMTP case there is no retry ambiguity here, because
            // a webhook receipt is addressed by the header we sent.
            return DeliveryResult.sent(notification.deliveryId().toString());
        }

        FailureClass failureClass = HttpFailureClassifier.classify(response.getStatusCode());
        String detail = "webhook endpoint returned " + response.getStatusCode().value() + ": "
                + truncate(response.getBody());
        return failureClass == FailureClass.RETRYABLE
                ? DeliveryResult.retryable(detail)
                : DeliveryResult.terminal(detail);
    }

    private Map<String, Object> body(RenderedNotification notification) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deliveryId", notification.deliveryId().toString());
        body.put("event", notification.templateKey());
        body.put("category", notification.category());
        body.put("locale", notification.recipientLocale());
        body.put("subject", notification.subject());
        body.put("body", notification.htmlBody());
        body.put("plainBody", notification.textBody());
        body.put("sentAt", Instant.now().toString());
        body.putAll(notification.headers());
        return body;
    }

    private static URI validTarget(String address) throws URISyntaxException {
        URI uri = new URI(address);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("scheme must be http or https, was '" + scheme + "'");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("no host");
        }
        return uri;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "<no body>";
        }
        return body.length() <= MAX_ERROR_DETAIL ? body : body.substring(0, MAX_ERROR_DETAIL) + "...";
    }
}
