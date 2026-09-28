package ru.ludwigandreas.usersettings.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One consent decision as the API renders it.
 *
 * <p>The evidence fields are not here. They exist to be produced under legal process, from the
 * database, not to be served to whoever is looking at a settings page - including the subject, whose
 * own address it is: echoing it back adds nothing they do not know and puts it in another log.
 */
public record ConsentResponse(
        UUID consentId,
        String consentKey,
        String textVersion,
        String decision,
        String locale,
        Instant occurredAt) {
}
