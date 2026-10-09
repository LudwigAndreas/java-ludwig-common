package ru.ludwigandreas.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.notification.service.announcement.AnnouncementService;
import ru.ludwigandreas.notification.web.dto.AnnouncementResponse;
import ru.ludwigandreas.notification.web.dto.AnnouncementSummaryResponse;
import ru.ludwigandreas.notification.web.mapper.AnnouncementDtoMapper;
import ru.ludwigandreas.odatafilter.core.ODataQueryOptions;
import ru.ludwigandreas.odatafilter.execution.ODataPage;
import ru.ludwigandreas.webcore.web.PageResponse;

/**
 * The announcements addressed to the caller.
 *
 * <h2>Why this is not part of the inbox</h2>
 *
 * <p>Two reasons, and the second is the one that decided it.
 *
 * <p>A single merged feed would need a {@code UNION} to page, sort and count across two sources.
 * JPQL has none and QueryDSL-JPA generates JPQL, so one feed would have meant a third native-SQL
 * carve-out - and merging two queries in the service layer instead cannot produce a correct absolute
 * position or total, which the platform's query contract requires.
 *
 * <p>More importantly, the inbox's correctness rests on one obviously-right predicate:
 * {@code owner = me}. An announcement has no owner row; who may see it is <em>derived</em> from role
 * membership. Threading that into the inbox would replace a filter nobody needs to think about with
 * one that has to be reviewed, on the endpoint every client polls. An ArchUnit rule forbids the inbox
 * path from depending on the announcement entity, so that stays true.
 *
 * <p>The practical cost is that a client shows two feeds and sums two badge numbers. That is probably
 * also the right product shape: an announcement is a banner or a "what's new" panel, not a line in
 * the bell dropdown.
 *
 * <h2>Authorization is authentication</h2>
 *
 * <p>{@code isAuthenticated()} and no role, as for the inbox. Reading the announcements addressed to
 * you is not a privilege anybody grants - a role here would either be given to everybody and mean
 * nothing, or be forgotten for somebody who then silently stops seeing platform notices. <b>The
 * audience predicate is the authorization</b>, and it lives where the rows are.
 *
 * <p>Note what that means: {@code ROLE} announcements are already role-gated, by the audience rather
 * than by the endpoint. A caller without the role gets an empty page, not a 403 - which is also why a
 * foreign announcement and a nonexistent one return the same 404.
 */
@Tag(name = "Announcements", description = "Platform announcements addressed to the caller")
@RestController
@RequestMapping("/api/v1/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;
    private final AnnouncementDtoMapper mapper;

    @Operation(summary = "List the announcements addressed to the caller",
            description = "Newest first, dismissed ones excluded, and only those inside their "
                    + "visibility window. Visibility is resolved from the caller's CURRENT roles on "
                    + "every request, so a revoked role stops granting it immediately. Supports "
                    + "$filter/$orderby/$top/$skip over the published surface - the audience is "
                    + "deliberately not filterable.")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public PageResponse<AnnouncementResponse> list(ODataQueryOptions options) {
        ODataPage<AnnouncementResponse> page =
                announcementService.list(options).map(mapper::toResponse);
        return PageResponse.of(page.content(), page.offset(), page.size(), page.totalElements());
    }

    /**
     * Its own endpoint rather than something a client derives from a page, for the same reason the
     * inbox's count is: rendering a banner or a badge needs the number and none of the content.
     */
    @Operation(summary = "Count the caller's outstanding announcements",
            description = "Returns no content. Dismissed announcements are not counted. Separate "
                    + "from the inbox count because the two feeds are separate; summing them is the "
                    + "client's decision.")
    @GetMapping("/outstanding-count")
    @PreAuthorize("isAuthenticated()")
    public AnnouncementSummaryResponse outstandingCount() {
        return new AnnouncementSummaryResponse(announcementService.outstanding());
    }

    @Operation(summary = "Read one announcement",
            description = "In the caller's own language, falling back to the deployment default. "
                    + "Includes announcements the caller has dismissed, because dismissing is not "
                    + "deleting. Answers 404 both when the announcement does not exist and when it "
                    + "is not addressed to this caller - the two are deliberately "
                    + "indistinguishable, so that a status code cannot be used to map which roles "
                    + "the platform addresses.")
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public AnnouncementResponse get(@PathVariable UUID id) {
        return mapper.toResponse(announcementService.get(id));
    }

    @Operation(summary = "Dismiss one announcement",
            description = "For this caller alone; everybody else still sees it. Writes one row, and "
                    + "only on the first dismissal. Idempotent: a repeat leaves the original instant "
                    + "alone. Still retrievable by id afterwards.")
    @PostMapping("/{id}/dismiss")
    @PreAuthorize("isAuthenticated()")
    public AnnouncementResponse dismiss(@PathVariable UUID id) {
        return mapper.toResponse(announcementService.dismiss(id));
    }
}
