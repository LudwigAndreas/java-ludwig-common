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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.notification.service.RecipientAdminService;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.web.dto.ChannelTypeDto;
import ru.ludwigandreas.notification.web.dto.PreferenceRequest;
import ru.ludwigandreas.notification.web.dto.PreferenceResponse;
import ru.ludwigandreas.notification.web.dto.RecipientProfileRequest;
import ru.ludwigandreas.notification.web.dto.RecipientProfileResponse;
import ru.ludwigandreas.notification.web.mapper.NotificationDtoMapper;

/**
 * Contact records and opt-outs.
 *
 * <p>Both are write-protected for the same reason from opposite directions: a write to a contact
 * record can redirect somebody's password reset to an attacker's mailbox, and a write to a preference
 * can silence their security alerts. Neither is a low-stakes CRUD endpoint, which is why both are
 * behind a role and both are logged.
 *
 * <p>Reads mask every address - see {@code NotificationDtoMapper}. An operator confirming the right
 * address is on file can do so from a masked value; a compromised support account cannot use this to
 * enumerate the customer base.
 */
@Tag(name = "Recipients", description = "Contact records, preferences and quiet hours")
@RestController
@RequestMapping("/api/v1/notifications/recipients")
@RequiredArgsConstructor
public class RecipientAdminController {

    private final RecipientAdminService recipientService;
    private final NotificationDtoMapper mapper;

    @Operation(summary = "Read a recipient's contact record",
            description = "Addresses are masked; this endpoint confirms what is on file, it does not "
                    + "hand out usable destinations.")
    @GetMapping("/{userId}")
    @PreAuthorize("hasAnyRole('NOTIFICATION_SUPPORT', 'NOTIFICATION_ADMIN')")
    public ResponseEntity<RecipientProfileResponse> profile(@PathVariable String userId) {
        return recipientService.profile(userId)
                .map(mapper::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Creates or replaces a contact record.
     *
     * <p>{@code PUT} with an upsert, because the caller - typically the user-profile service reacting
     * to its own change event - has no reason to know whether this service has seen the user before,
     * and making it find out first adds a round trip whose answer can change between the two calls.
     */
    @Operation(summary = "Create or replace a recipient's contact record")
    @PutMapping("/{userId}")
    @PreAuthorize("hasAnyRole('NOTIFICATION_RECIPIENT_WRITER', 'NOTIFICATION_ADMIN')")
    public RecipientProfileResponse upsert(@PathVariable String userId,
                                           @Valid @RequestBody RecipientProfileRequest request) {
        return mapper.toResponse(recipientService.upsertProfile(
                userId, request.emailAddress(), request.chatAddress(), request.webhookUrl(),
                request.locale(), request.timezone(),
                request.quietHoursStart(), request.quietHoursEnd()));
    }

    @Operation(summary = "List a recipient's preferences")
    @GetMapping("/{userId}/preferences")
    @PreAuthorize("hasAnyRole('NOTIFICATION_SUPPORT', 'NOTIFICATION_ADMIN')")
    public List<PreferenceResponse> preferences(@PathVariable String userId) {
        return mapper.toPreferenceResponses(recipientService.preferences(userId));
    }

    @Operation(summary = "Set one preference",
            description = "allowed=false is an opt-out; allowed=true is an explicit opt-in that beats "
                    + "a wildcard opt-out.")
    @PutMapping("/{userId}/preferences")
    @PreAuthorize("hasAnyRole('NOTIFICATION_RECIPIENT_WRITER', 'NOTIFICATION_ADMIN')")
    public PreferenceResponse setPreference(@PathVariable String userId,
                                            @Valid @RequestBody PreferenceRequest request) {
        return mapper.toResponse(recipientService.setPreference(
                userId, request.category(), mapper.toChannel(request.channel()), request.allowed(),
                request.source() == null || request.source().isBlank() ? "support" : request.source()));
    }

    /**
     * Removes a preference, restoring the default.
     *
     * <p>Distinct from setting it to allowed: "I never said anything about this" and "I explicitly
     * want this" behave identically today, and only the second survives a later wildcard opt-out.
     */
    @Operation(summary = "Clear one preference, restoring the default")
    @DeleteMapping("/{userId}/preferences")
    @PreAuthorize("hasAnyRole('NOTIFICATION_RECIPIENT_WRITER', 'NOTIFICATION_ADMIN')")
    public ResponseEntity<Void> clearPreference(@PathVariable String userId,
                                                @RequestParam String category,
                                                @RequestParam(required = false) ChannelTypeDto channel) {
        ChannelType resolved = channel == null ? null : mapper.toChannel(channel);
        boolean cleared = recipientService.clearPreference(userId, category, resolved);
        return cleared ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
