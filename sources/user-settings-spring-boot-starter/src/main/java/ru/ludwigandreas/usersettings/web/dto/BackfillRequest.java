package ru.ludwigandreas.usersettings.web.dto;

import java.util.Set;

/**
 * The body of an administrative backfill call.
 *
 * <p><b>There is no tenant field, deliberately.</b> The controller pins the caller's own tenant, the
 * same way every other endpoint in this module does - a field here would turn tenant isolation into
 * something the server has to remember to check rather than something a caller cannot express. The
 * estate-wide form of a backfill exists, but it is reachable only from in-process operator code.
 *
 * <p>Every field is optional; an empty body means "republish everything for my tenant, unbounded".
 *
 * @param settingKeys     which settings to republish; empty or null means all of them
 * @param consentKeys     which consents to republish; empty or null means all of them
 * @param includeSettings defaults to true
 * @param includeConsents defaults to true
 * @param batchSize       rows per transaction; null takes the module default
 * @param maxRows         stop after roughly this many rows, so the call returns before an HTTP client
 *                        gives up on it; null or 0 runs to completion
 */
public record BackfillRequest(
        Set<String> settingKeys,
        Set<String> consentKeys,
        Boolean includeSettings,
        Boolean includeConsents,
        Integer batchSize,
        Long maxRows) {
}
