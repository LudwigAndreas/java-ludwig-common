package ru.ludwigandreas.usersettings.consent;

import java.time.Instant;
import java.util.UUID;
import ru.ludwigandreas.usersettings.api.ConsentDecision;

/**
 * One decision from the ledger, as the service layer sees it.
 *
 * <p>A model rather than the entity, because the entity carries persistence concerns - the
 * provenance columns, the {@code Persistable} flag, a lazily-initialized identity - that a caller
 * has no business seeing and that would tie an admin screen or a REST response to the schema.
 *
 * @param consentId   the owner's id for this decision; the same value in every projection of it
 * @param textVersion the version of the wording agreed to
 * @param occurredAt  when the person decided
 * @param recordedAt  when this deployment learned of it, which in a projection is later
 */
public record ConsentRecord(
        UUID consentId,
        String subject,
        String tenantId,
        String consentKey,
        String textVersion,
        ConsentDecision decision,
        String locale,
        Instant occurredAt,
        Instant recordedAt,
        String actor) {

    public boolean isGranted() {
        return decision == ConsentDecision.GRANTED;
    }
}
