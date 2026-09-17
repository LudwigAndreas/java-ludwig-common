package ru.ludwigandreas.notification.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One opt-out or explicit opt-in.
 *
 * @param category the category, or {@code *} for every category the recipient may decline
 * @param channel  the channel it applies to; omit for all of them
 * @param allowed  false is an opt-out; true is an explicit opt-in that beats a wildcard opt-out, so
 *                 "nothing except order updates by email" is two rows rather than a deletion
 * @param source   how it was set - {@code self-service}, {@code support}, {@code import} - which is
 *                 what a complaint about an unwanted email is actually answered with
 */
public record PreferenceRequest(

        @NotBlank(message = "{notification.validation.preference.category.required}")
        @Size(max = 128, message = "{notification.validation.category.size}")
        String category,

        ChannelTypeDto channel,

        boolean allowed,

        @Size(max = 64, message = "{notification.validation.preference.source.size}")
        String source) {
}
