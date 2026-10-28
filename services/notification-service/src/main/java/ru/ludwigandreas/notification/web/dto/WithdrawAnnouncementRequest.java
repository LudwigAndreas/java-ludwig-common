package ru.ludwigandreas.notification.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * What an announcer sends to withdraw an announcement.
 *
 * @param reason optional, because a withdrawal must never wait on prose; recorded and audited when
 *               given
 */
@Schema(name = "WithdrawAnnouncementRequest",
        description = "Withdraw a published announcement. The reason is optional.")
public record WithdrawAnnouncementRequest(

        @Size(max = 500)
        String reason) {
}
