package ru.ludwigandreas.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import ru.ludwigandreas.notification.service.NotificationService;
import ru.ludwigandreas.notification.service.model.IngressSource;
import ru.ludwigandreas.notification.service.model.NotificationRequestView;
import ru.ludwigandreas.notification.web.dto.NotificationRequestResponse;
import ru.ludwigandreas.notification.web.dto.RenderPreviewRequest;
import ru.ludwigandreas.notification.web.dto.RenderPreviewResponse;
import ru.ludwigandreas.notification.web.dto.SendNotificationRequest;
import ru.ludwigandreas.notification.web.mapper.NotificationDtoMapper;
import ru.ludwigandreas.observability.correlation.CorrelationContext;

/**
 * The synchronous ingress: for on-demand sends, and for anything a human triggered and is waiting on.
 *
 * <p>It validates, maps and delegates. No business rule, no transaction and no query lives here -
 * everything this endpoint does is also done by the Kafka listener, and the only way to keep the two
 * genuinely identical is for neither of them to decide anything.
 *
 * <h2>Authorization</h2>
 *
 * <p>Peer services arrive as {@code PrincipalType.SERVICE} with roles resolved from the local
 * projection, and the {@code @PreAuthorize} below is written against a role rather than a shared
 * secret. That distinction is the point of the security starter: a shared secret is one value that
 * every caller holds, cannot be revoked for one of them, and appears in as many config maps as there
 * are callers. A workload identity is per-caller, revocable, and already carried by the mesh.
 */
@Tag(name = "Notifications", description = "Submit notification requests and preview templates")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    /**
     * The header carrying the caller's dedup key.
     *
     * <p>{@code Idempotency-Key} is the conventional spelling, and it is a header rather than a body
     * field so it is visible to a proxy and cannot be confused with the payload it protects.
     */
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final NotificationService notificationService;
    private final NotificationDtoMapper mapper;
    private final CorrelationContext correlationContext;

    /**
     * Accepts a request and fans it out.
     *
     * <p>202, not 201, and the distinction is honest rather than pedantic: what has been created is a
     * queued intention, and whether anything reaches anybody is decided minutes later by a provider
     * this service does not control. A 201 would promise delivery, which no notification service can.
     *
     * <p>A duplicate answers 200 with the original request rather than 202, so a caller retrying after
     * a timeout can tell whether their retry was the one that did the work.
     */
    @Operation(summary = "Submit a notification request",
            description = "Fans the request out into one delivery per recipient per channel. "
                    + "Supply Idempotency-Key to make a retry safe.")
    @PostMapping
    @PreAuthorize("hasAnyRole('NOTIFICATION_SENDER', 'NOTIFICATION_ADMIN')")
    public ResponseEntity<NotificationRequestResponse> submit(
            @Valid @RequestBody SendNotificationRequest request,
            @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            UriComponentsBuilder uriBuilder) {

        NotificationRequestView view = notificationService.submit(mapper.toCommand(
                request, idempotencyKey, IngressSource.REST,
                correlationContext.currentId().orElse(null), null));

        NotificationRequestResponse body = mapper.toResponse(view);
        if (view.duplicate()) {
            return ResponseEntity.ok(body);
        }
        URI location = uriBuilder.path("/api/v1/notifications/{id}").build(view.id());
        return ResponseEntity.accepted().location(location).body(body);
    }

    /**
     * Renders a template without sending anything.
     *
     * <p>Restricted to authors and admins rather than to every sender: a preview returns fully
     * rendered text for arbitrary variables, so a caller who could reach it could use this service as
     * a template engine for content it then sends itself - which is the coupling the template-key
     * contract exists to prevent.
     */
    @Operation(summary = "Preview a rendered template",
            description = "Renders through the same resolver and the same strictness as a live send, "
                    + "so a preview that succeeds guarantees the send would.")
    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('NOTIFICATION_AUTHOR', 'NOTIFICATION_ADMIN')")
    public RenderPreviewResponse preview(@Valid @RequestBody RenderPreviewRequest request) {
        return mapper.toResponse(notificationService.preview(mapper.toRenderRequest(request)));
    }
}
