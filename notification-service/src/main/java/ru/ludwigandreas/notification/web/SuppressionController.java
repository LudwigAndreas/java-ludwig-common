package ru.ludwigandreas.notification.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.notification.service.preference.SuppressionService;
import ru.ludwigandreas.notification.web.dto.ChannelTypeDto;
import ru.ludwigandreas.notification.web.dto.SuppressionRequest;
import ru.ludwigandreas.notification.web.dto.SuppressionResponse;
import ru.ludwigandreas.notification.web.mapper.NotificationDtoMapper;

/**
 * The suppression list: destinations this service will not write to.
 *
 * <p>Normally fed by the receipt webhook rather than by hand; these endpoints exist for the cases the
 * provider cannot tell us about - a recipient who asks support to stop, or an address that was
 * suppressed on a bounce that turned out to be somebody else's misconfiguration.
 *
 * <p>Releasing is admin-only and adding is not, deliberately: putting an address on the list makes
 * this service send less, which is at worst inconvenient, while taking one off makes it send to an
 * address that something previously judged undeliverable - and getting that wrong costs the sending
 * domain's reputation for everybody.
 */
@Tag(name = "Suppression list", description = "Destinations that will not be written to")
@RestController
@RequestMapping("/api/v1/notifications/suppressions")
@RequiredArgsConstructor
public class SuppressionController {

    private final SuppressionService suppressionService;
    private final NotificationDtoMapper mapper;

    @Operation(summary = "List active suppressions for a channel",
            description = "Addresses are masked.")
    @GetMapping
    @PreAuthorize("hasAnyRole('NOTIFICATION_SUPPORT', 'NOTIFICATION_ADMIN')")
    public List<SuppressionResponse> list(@RequestParam ChannelTypeDto channel) {
        return mapper.toSuppressionResponses(suppressionService.list(mapper.toChannel(channel)));
    }

    @Operation(summary = "Suppress a destination",
            description = "Omit expiresAt for a permanent suppression - the right choice for a "
                    + "complaint, the wrong one for a full mailbox.")
    @PostMapping
    @PreAuthorize("hasAnyRole('NOTIFICATION_SUPPORT', 'NOTIFICATION_ADMIN')")
    public SuppressionResponse suppress(@Valid @RequestBody SuppressionRequest request) {
        return mapper.toResponse(suppressionService.suppress(
                mapper.toChannel(request.channel()), request.address(), request.reason(),
                request.detail(), request.expiresAt()));
    }

    @Operation(summary = "Release a suppression",
            description = "Admin only: this makes the service send to an address something previously "
                    + "judged undeliverable.")
    @DeleteMapping
    @PreAuthorize("hasRole('NOTIFICATION_ADMIN')")
    public ResponseEntity<Void> release(@RequestParam ChannelTypeDto channel,
                                        @RequestParam String address) {
        boolean released = suppressionService.release(mapper.toChannel(channel), address);
        return released ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
