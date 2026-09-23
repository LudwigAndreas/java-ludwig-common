package ru.ludwigandreas.usersettings.web.dto;

import java.util.List;

/**
 * Every setting the service declares, resolved for one subject.
 *
 * <p>A list rather than a bare map, so the response has somewhere to grow - a later addition of
 * paging, of an {@code updatedAt}, or of the subject the settings belong to does not change the
 * shape a client already parses.
 */
public record SettingsResponse(List<SettingValueResponse> settings) {
}
