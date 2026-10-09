package ru.ludwigandreas.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.notification.service.announcement.AnnouncementAdminService;
import ru.ludwigandreas.notification.service.announcement.AnnouncementEmailBroadcastService;
import ru.ludwigandreas.notification.service.exception.AnnouncementNotFoundException;
import ru.ludwigandreas.notification.service.model.AnnouncementAdminView;
import ru.ludwigandreas.notification.service.model.AudienceType;
import ru.ludwigandreas.notification.service.model.BroadcastRunView;
import ru.ludwigandreas.notification.web.dto.AnnouncementAdminResponse;
import ru.ludwigandreas.notification.web.dto.PublishAnnouncementRequest;
import ru.ludwigandreas.notification.web.mapper.AnnouncementDtoMapper;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.webcore.operation.OperationLocationHeader;
import ru.ludwigandreas.webcore.operation.OperationProgress;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationResponses;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * Publishing and correcting announcements.
 *
 * <h2>A role of its own, not the notification admin's</h2>
 *
 * <p>{@code ROLE_NOTIFICATION_ANNOUNCER} rather than {@code ROLE_NOTIFICATION_ADMIN}, because the two
 * are different powers. A notification admin inspects delivery history and retries dead letters -
 * operational work on messages other services asked for. An announcer originates a message to the
 * whole organisation, and possibly emails all of it. Support staff need the first and must not
 * silently acquire the second.
 *
 * <p>Holding the role is not the whole check: <em>what</em> may be announced is also constrained, by
 * the audience allowlist and the category catalogue, and a caller with this role can still be refused
 * an audience. The role says "this person may make announcements", not "this person may make any
 * announcement".
 *
 * <h2>The request carries data; the deployment carries policy</h2>
 *
 * <p>There is no {@code channels} field and no flag asking for email - see
 * {@link PublishAnnouncementRequest}. The category decides both, from a catalogue somebody reviewed.
 */
@Tag(name = "Announcement administration", description = "Publish and correct platform announcements")
@RestController
@RequestMapping("/api/v1/announcements")
@RequiredArgsConstructor
public class AnnouncementAdminController {

    private final AnnouncementAdminService adminService;
    private final AnnouncementDtoMapper mapper;
    private final AnnouncementEmailBroadcastService broadcastService;
    private final NotificationProperties properties;

    /**
     * Publishes one announcement.
     *
     * <p>{@code 201} and not {@code 202}: for an inbox-only category the whole thing is done when
     * this returns - the announcement is one row and it is already visible. A category that also
     * emails starts a fan-out, and that operation's identifier is on the response for the caller to
     * poll; the announcement itself is still complete either way, which is why this is not a 202
     * promising later work.
     */
    @Operation(summary = "Publish an announcement",
            description = "The category decides the class and the channels - the request cannot. "
                    + "Returns 201 with the announcement, which is already visible if its window has "
                    + "opened. Where the category includes EMAIL, emailRunId names the fan-out "
                    + "operation to poll.")
    @PostMapping
    @PreAuthorize("hasRole('NOTIFICATION_ANNOUNCER')")
    public ResponseEntity<AnnouncementAdminResponse> publish(
            @Valid @RequestBody PublishAnnouncementRequest request) {
        AnnouncementAdminService.PublishedAnnouncement published = adminService.publish(
                request.category(),
                AudienceType.valueOf(request.audienceType().name()),
                request.audienceValue(),
                request.templateKey(),
                request.variables(),
                request.visibleFrom(),
                request.visibleUntil());

        AnnouncementAdminResponse body =
                mapper.toAdminResponse(published.announcement(), published.emailRunId());

        if (published.emailRunId() == null) {
            // Inbox-only: the announcement is one row and it is already visible, so there is nothing
            // to poll. A 202 here would promise later work that does not exist.
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }

        // The category also emails, so there IS an operation - and it genuinely is one: it runs for
        // minutes, has progress and can be cancelled. Built with OperationResponses so the envelope
        // cannot be malformed; it refuses a 202 with no status-resource header, which is exactly the
        // mistake that would leave a caller with a run they cannot find.
        //
        // Note this is a 202 carrying a body whose announcement is already complete. The operations
        // capability permits that explicitly, and notification already does it when it fans out in
        // the accepting transaction: the inbox half is done, the email half is not.
        OperationResponse operation = operationFor(published.announcement().id());
        // OPERATION_LOCATION rather than LOCATION, because the 201-shaped body IS the created
        // announcement: a Location header would be read as pointing at the thing just created, and
        // here the thing to poll is a different resource from the thing created.
        return OperationResponses.accepted(operation, body,
                statusUri(published.announcement().id()),
                OperationLocationHeader.OPERATION_LOCATION);
    }

    /**
     * Re-renders a published announcement's content.
     *
     * <p>{@code POST} on a sub-resource rather than {@code PATCH} on the announcement, because it is
     * a named command - "render this again" - and not a partial edit of the document. The audience
     * and the window are untouched and cannot be changed this way: part of the audience may already
     * have been emailed, so moving an announcement between audiences would make the record of who was
     * told untrue.
     */
    @Operation(summary = "Correct a published announcement's content",
            description = "Re-renders every supported locale together, so the languages cannot drift "
                    + "apart. Dismissals are preserved - a typo fix does not un-dismiss anything. "
                    + "The audience and window cannot be changed.")
    @PostMapping("/{id}/corrections")
    @PreAuthorize("hasRole('NOTIFICATION_ANNOUNCER')")
    public AnnouncementAdminResponse correct(@PathVariable UUID id,
                                             @RequestBody(required = false)
                                             PublishAnnouncementRequest request) {
        AnnouncementAdminView corrected = adminService.correct(id,
                request == null ? null : request.variables());
        return mapper.toAdminResponse(corrected,
                broadcastService.runFor(id).map(BroadcastRunView::id).orElse(null));
    }

    /**
     * The email fan-out's status.
     *
     * <p>The status resource the publish's 202 named. Answers a non-terminal poll with a retry hint
     * and progress, and a terminal one with neither - {@code OperationResponses} enforces both, which
     * is why the envelope is built rather than assembled.
     */
    @Operation(summary = "Poll an announcement's email fan-out",
            description = "Progress while it runs, terminal when it has finished. The total is "
                    + "nullable until the audience has been walked, because counting a hundred "
                    + "thousand rows up front would delay the first send to produce a number nobody "
                    + "is waiting for.")
    @GetMapping("/{id}/email-run")
    @PreAuthorize("hasRole('NOTIFICATION_ANNOUNCER')")
    public ResponseEntity<OperationResponse> emailRun(@PathVariable UUID id) {
        BroadcastRunView run = broadcastService.runFor(id)
                .orElseThrow(() -> new AnnouncementNotFoundException(id));
        OperationResponse operation = toEnvelope(run);
        return run.isTerminal()
                ? OperationResponses.completed(operation)
                : OperationResponses.poll(operation, pollInterval());
    }

    /**
     * Requests that the fan-out stop.
     *
     * <p><b>202, not 204</b>, and that is the platform's rule rather than a preference: cancellation
     * is cooperative, so the stop has been <em>requested</em> and not performed. The loop notices
     * between batches.
     *
     * <p>Deliveries already created are not recalled - they are committed work, and silently dropping
     * them would make the count the envelope already reported untrue. Cancelling an already-terminal
     * run returns its envelope rather than a conflict.
     */
    @Operation(summary = "Cancel an announcement's email fan-out",
            description = "Answers 202 because the stop is requested, not performed. Deliveries "
                    + "already created are NOT recalled; the envelope reports how many there were. "
                    + "Cancelling a finished run returns its envelope rather than a 409.")
    @PostMapping("/{id}/email-run/cancellation")
    @PreAuthorize("hasRole('NOTIFICATION_ANNOUNCER')")
    public ResponseEntity<OperationResponse> cancelEmailRun(@PathVariable UUID id) {
        BroadcastRunView run = broadcastService.requestCancellation(id);
        return run.isTerminal()
                ? OperationResponses.completed(toEnvelope(run))
                : OperationResponses.cancellationRequested(toEnvelope(run));
    }

    /**
     * The retry hint, taken from the fan-out's own schedule rather than invented.
     *
     * <p>A caller told to come back sooner than the job can possibly have advanced is being asked to
     * poll for nothing, and one told to come back much later sees stale progress. The interval the
     * scheduler actually runs on is the only honest answer.
     */
    private Duration pollInterval() {
        return properties.getAnnouncements().getFanOut().getRunInterval();
    }

    private OperationResponse operationFor(UUID announcementId) {
        return broadcastService.runFor(announcementId)
                .map(AnnouncementAdminController::toEnvelope)
                .orElseThrow(() -> new AnnouncementNotFoundException(announcementId));
    }

    /**
     * The run as the platform's envelope.
     *
     * <p>The status is {@link OperationStatus} itself, not a translation of a local enum - this
     * service declares none, and {@code RuleGroup.OPERATIONS} fails the build on a restatement.
     *
     * <p>The progress total is left null until the audience has been counted, which is what that
     * field's nullability is for: an operation that does not yet know its total should say so rather
     * than report a guess that later moves.
     */
    private static OperationResponse toEnvelope(BroadcastRunView run) {
        return OperationResponse.builder()
                .id(run.id().toString())
                .status(run.status())
                .detail(run.lastError())
                .progress(run.audienceTotal() == null
                        ? OperationProgress.of(run.deliveriesCreated(), "deliveries")
                        : OperationProgress.of(run.deliveriesCreated(), run.audienceTotal(),
                                "deliveries"))
                .submittedAt(run.submittedAt())
                .startedAt(run.startedAt())
                .finishedAt(run.finishedAt())
                .build();
    }

    private static URI statusUri(UUID announcementId) {
        return URI.create("/api/v1/announcements/" + announcementId + "/email-run");
    }
}
