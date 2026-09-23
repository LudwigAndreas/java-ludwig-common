package ru.ludwigandreas.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import ru.ludwigandreas.notification.service.NotificationService;
import ru.ludwigandreas.notification.service.exception.BatchTooLargeException;
import ru.ludwigandreas.notification.service.model.IngressSource;
import ru.ludwigandreas.notification.service.model.NotificationRequestView;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.web.dto.BatchItemResult;
import ru.ludwigandreas.notification.web.dto.BatchSendRequest;
import ru.ludwigandreas.notification.web.dto.BatchSendResponse;
import ru.ludwigandreas.notification.web.dto.NotificationRequestResponse;
import ru.ludwigandreas.notification.web.dto.RenderPreviewRequest;
import ru.ludwigandreas.notification.web.dto.RenderPreviewResponse;
import ru.ludwigandreas.notification.web.dto.SendNotificationRequest;
import ru.ludwigandreas.notification.web.mapper.NotificationDtoMapper;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemCodes;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemDetailFactory;
import ru.ludwigandreas.webcore.problem.ProblemMapperRegistry;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The synchronous ingress, and on a deployment without a broker the only one.
 *
 * <p>It validates, maps and delegates. No business rule, no transaction and no query lives here -
 * everything this endpoint does is also done by the Kafka listener where that is enabled, and the
 * only way to keep the two genuinely identical is for neither of them to decide anything.
 *
 * <h2>Three endpoints, one contract</h2>
 *
 * <p>{@code POST /} is one notification. {@code POST /batch} is several of them travelling together,
 * for a caller replacing a topic producer; each item goes through the same application service, so a
 * request submitted in a batch is indistinguishable afterwards from one submitted alone.
 * {@code GET /{id}} is what makes the 202 answerable: an asynchronous accept is only useful if the
 * location it hands back resolves.
 *
 * <h2>Authorization</h2>
 *
 * <p>Peer services arrive as {@code PrincipalType.SERVICE} with roles resolved from the local
 * projection, and the {@code @PreAuthorize} below is written against a role rather than a shared
 * secret. That distinction is the point of the security starter: a shared secret is one value that
 * every caller holds, cannot be revoked for one of them, and appears in as many config maps as there
 * are callers. A workload identity is per-caller, revocable, and already carried by the mesh.
 *
 * <p>The read is authorized twice, and both are needed. The role check here says the caller may use
 * this endpoint at all; the data-scope check inside the service says which requests this particular
 * caller may see - without it, any holder of the sender role could read any tenant's request by id.
 */
@Slf4j
@Tag(name = "Notifications", description = "Submit notification requests and preview templates")
@RestController
@RequestMapping(NotificationController.BASE_PATH)
@RequiredArgsConstructor
public class NotificationController {

    /**
     * The header carrying the caller's dedup key.
     *
     * <p>{@code Idempotency-Key} is the conventional spelling, and it is a header rather than a body
     * field so it is visible to a proxy and cannot be confused with the payload it protects. The
     * batch endpoint is the one exception - see {@link BatchSendRequest.BatchItem}.
     */
    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    /**
     * The collection this controller is mounted on, and the root of the two paths below.
     *
     * <p>Package-private rather than private because {@code @RequestMapping} above refers to it: a
     * constant in an annotation has to be visible where the annotation is read, and one literal that
     * the mapping and the {@code Location} header both derive from cannot drift apart.
     */
    static final String BASE_PATH = "/api/v1/notifications";

    private static final String REQUEST_PATH = BASE_PATH + "/{id}";

    private static final String BATCH_PATH = BASE_PATH + "/batch";

    private final NotificationService notificationService;
    private final NotificationDtoMapper mapper;
    private final CorrelationContext correlationContext;
    private final ProblemDetailFactory problems;
    private final ProblemMapperRegistry problemMappers;
    private final NotificationProperties properties;

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

        NotificationRequestView view = submitOne(request, idempotencyKey);

        NotificationRequestResponse body = mapper.toResponse(view);
        if (view.duplicate()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.accepted()
                .location(location(uriBuilder, view.id()))
                .body(body);
    }

    /**
     * Accepts several unrelated requests in one call.
     *
     * <p>Always 200, whatever the items did, because there is no status code that describes a mixed
     * outcome: 202 would claim every item was accepted and a 4xx would claim none was. The status
     * answers "was the batch understood" and the body answers "what happened to each item" - see
     * {@link BatchSendResponse}.
     *
     * <h2>Each item is its own transaction</h2>
     *
     * <p>This method is deliberately not transactional. Every call to the application service opens
     * and commits its own transaction, so an item that fails rolls back only itself and leaves the
     * items before it committed and delivered. Wrapping the loop in one transaction would make a
     * single bad template key discard ninety-nine accepted notifications - and, worse, would do so
     * after they had already been reported as accepted in the response being built.
     *
     * <h2>Why failures are caught here</h2>
     *
     * <p>An exception that escaped would become the whole response, which is the outcome the endpoint
     * exists to avoid. It is rendered through the same problem pipeline the single endpoint uses, so
     * a caller reads one error shape in both places. {@code failFast} is available for a caller that
     * genuinely wants the first failure to stop the rest, and defaults to off - see
     * {@code NotificationProperties.Rest}.
     */
    @Operation(summary = "Submit several notification requests",
            description = "For callers replacing a message producer. Each item is submitted "
                    + "independently and reported independently; the response is always 200 and "
                    + "carries a per-item outcome.")
    @PostMapping("/batch")
    @PreAuthorize("hasAnyRole('NOTIFICATION_SENDER', 'NOTIFICATION_ADMIN')")
    public ResponseEntity<BatchSendResponse> submitBatch(@Valid @RequestBody BatchSendRequest batch) {
        int limit = properties.getIngress().getRest().getMaxBatchSize();
        if (batch.items().size() > limit) {
            // A LocalizedException rather than a 413 from a container limit: the caller needs to know
            // the number, in its own language, and needs it to be the same number the documentation
            // states rather than whatever a proxy happened to enforce.
            throw new BatchTooLargeException(batch.items().size(), limit);
        }

        List<BatchItemResult> results = new ArrayList<>(batch.items().size());
        boolean failFast = properties.getIngress().getRest().isFailFast();

        for (int index = 0; index < batch.items().size(); index++) {
            BatchSendRequest.BatchItem item = batch.items().get(index);
            try {
                NotificationRequestView view = submitOne(item.request(), item.idempotencyKey());
                results.add(BatchItemResult.accepted(index, item.reference(), mapper.toResponse(view)));
            } catch (RuntimeException e) {
                // Logged at warn with the index and the caller's own reference, because the response
                // body is the caller's copy of this and the operator needs one too. The problem
                // detail deliberately carries no exception message - see renderProblem.
                log.warn("Batch item {} (reference {}) was rejected", index, item.reference(), e);
                ProblemDetail problem = renderProblem(e, BATCH_PATH);
                results.add(BatchItemResult.rejected(index, item.reference(), problem));
                if (failFast) {
                    break;
                }
            }
        }
        return ResponseEntity.ok(BatchSendResponse.of(results));
    }

    /**
     * The status of a request and the deliveries it became.
     *
     * <p>The resource the 202 above points at. Scoped in the service layer rather than here: an
     * authorization that depends on the row's contents can only be decided once the row is loaded.
     */
    @Operation(summary = "Read a submitted request",
            description = "The request's state and every delivery it fanned out into. This is the "
                    + "resource the Location header of a 202 refers to.")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('NOTIFICATION_SENDER', 'NOTIFICATION_SUPPORT', 'NOTIFICATION_ADMIN')")
    public NotificationRequestResponse get(@PathVariable UUID id) {
        return mapper.toResponse(notificationService.get(id));
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

    /**
     * The one place a wire request becomes a command, used by both submitting endpoints.
     *
     * <p>Shared so that the single and the batch endpoint cannot drift: the source, the correlation
     * id and the idempotency key are decided here, once, and neither endpoint is in a position to
     * decide them differently.
     */
    private NotificationRequestView submitOne(SendNotificationRequest request, String idempotencyKey) {
        return notificationService.submit(mapper.toCommand(
                request, idempotencyKey, IngressSource.REST,
                correlationContext.currentId().orElse(null), null));
    }

    /**
     * The submitted request's own URI.
     *
     * <p>Built by appending to the injected builder rather than by replacing its path: Spring hands
     * over the application's base URI, and a deployment behind a context path has that context path
     * in it. Replacing rather than appending would drop it and hand the caller a location that 404s
     * everywhere except a local run.
     */
    private URI location(UriComponentsBuilder uriBuilder, UUID id) {
        return uriBuilder.path(REQUEST_PATH).build(id);
    }

    /**
     * Renders a failed batch item exactly as the single endpoint would have rendered the same
     * failure.
     *
     * <h2>Why the controller does this at all</h2>
     *
     * <p>Spring's exception handling is per-request: an exception that escapes a controller method
     * becomes the response. A batch item's failure must not become the response - the other
     * ninety-nine items succeeded - so it is caught, and a caught exception never reaches the advice
     * that would have turned it into a {@link ProblemDetail}. This runs the same two steps that
     * advice runs, in the same order, against the same beans.
     *
     * <p>The alternative, putting the exception's message in a string field, is how a batch endpoint
     * ends up with an error contract of its own: untranslated, untyped, carrying whatever an
     * exception happened to say, and drifting from the single endpoint's every time a new exception
     * is mapped.
     *
     * <p>It stays a private method rather than becoming a collaborator because there is nowhere
     * honest to put one: a class beside this controller reads as a second controller, the mapper
     * package is for MapStruct interfaces, and the service layer must not know what a
     * {@link ProblemDetail} is. Two delegations and a fallback do not need a home of their own.
     *
     * <h2>Nothing is trusted to describe itself</h2>
     *
     * <p>An exception with no mapping becomes a generic 500 rather than a problem quoting its
     * message. An unmapped exception is by definition one nobody decided how to present, and its
     * message is as likely to contain a provider hostname, a recipient address or a SQL fragment as
     * anything useful. The trace id already in every problem is how it is tied back to the log line
     * that has the detail.
     *
     * @param requestUri the batch endpoint's own path, so {@code instance} points at the call that
     *                   was actually made rather than at a per-item URI nobody requested. A constant
     *                   rather than the request's builder: that builder is mutable and shared with
     *                   {@link #location}, and reading a path out of it inside a loop is how a URI
     *                   ends up with the same segment appended a hundred times
     */
    private ProblemDetail renderProblem(Throwable failure, String requestUri) {
        if (failure instanceof LocalizedException localized) {
            return problems.create(localized, requestUri);
        }
        return problemMappers.resolve(failure)
                .map(definition -> problems.create(definition, requestUri))
                .orElseGet(() -> problems.create(
                        ProblemDefinition.of(ProblemStatus.INTERNAL, ProblemCodes.INTERNAL), requestUri));
    }
}
