package ru.ludwigandreas.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.service.ReceiptService;

/**
 * Inbound provider callbacks: delivered, bounced, complained.
 *
 * <h2>Why the body is taken as a String</h2>
 *
 * <p>The signature covers the exact bytes the provider sent. Letting Spring deserialize into a DTO
 * and then re-serializing to verify would produce different bytes - a reordered field, a different
 * number format, a dropped unknown property - and every signature would fail. So the raw body is
 * verified first and parsed second, which is the only order that works.
 *
 * <p>Consequently the payload cannot be validated by {@code @Valid} on a parameter, and it is
 * validated explicitly instead. Skipping validation because the transport made it awkward is how an
 * endpoint that is already the most attackable surface in the service acquires a second problem.
 *
 * <h2>Why it is permitAll and why that is safe</h2>
 *
 * <p>A provider has no account here and cannot hold a token, so the endpoint is open at the filter
 * chain and authenticated by the HMAC instead. That is the entire authentication, which is why it is
 * strict about everything: a missing secret refuses rather than accepts, a stale timestamp is
 * rejected, and no failure says which check failed - an endpoint that distinguishes "bad signature"
 * from "unknown message" is an oracle for probing both.
 */
@Slf4j
@Tag(name = "Delivery receipts", description = "Provider callbacks - HMAC authenticated")
@RestController
@RequestMapping("/api/v1/notifications/receipts")
@RequiredArgsConstructor
public class DeliveryReceiptController {

    private final ReceiptService receiptService;
    private final NotificationProperties properties;

    @Operation(summary = "Accept a provider delivery receipt",
            description = """
                    Authenticated by an HMAC over '<timestamp>.<body>' in X-Provider-Signature, with
                    the epoch-second timestamp in X-Provider-Timestamp. Advances the delivery and, for
                    a bounce or a complaint, suppresses the address.

                    Body: {"channel":"EMAIL","providerMessageId":"<id>","outcome":"DELIVERED|BOUNCED|
                    DEFERRED|COMPLAINED","occurredAt":"<ISO-8601, optional>","detail":"<optional>"}
                    """)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> receive(@RequestBody String rawBody,
                                        @RequestHeader(name = "X-Provider-Signature", required = false)
                                        String signature,
                                        @RequestHeader(name = "X-Provider-Timestamp", required = false)
                                        String timestamp) {
        if (!properties.getReceipts().isEnabled()) {
            // Refused rather than silently accepted, so a provider configured against a deployment
            // that does not process receipts finds out instead of believing it is being heard.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        // Verification, parsing and application are one operation in the service: the signature covers
        // the exact bytes received, so nothing may deserialize them first.
        receiptService.accept(rawBody, signature, timestamp);
        return ResponseEntity.noContent().build();
    }
}
