package ru.ludwigandreas.usersettings.backfill;

import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.usersettings.entity.UserConsentEntity;
import ru.ludwigandreas.usersettings.entity.UserSettingValueEntity;
import ru.ludwigandreas.usersettings.event.ConsentChangedEvent;
import ru.ludwigandreas.usersettings.event.SettingsEventPublisher;
import ru.ludwigandreas.usersettings.event.UserSettingChangedEvent;
import ru.ludwigandreas.usersettings.repository.UserConsentRepository;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;

/**
 * Reads one page of stored rows and publishes it, in one transaction.
 *
 * <h2>Why this is a separate bean from {@link SettingsBackfillService}</h2>
 *
 * <p>Each batch has to commit on its own. A backfill can walk a table with millions of rows, and
 * doing that inside one transaction would hold it open for minutes against tables that are also
 * serving live writes, accumulate the whole run in the persistence context, and lose everything if
 * the last row failed. But the loop that drives the batches must itself be <em>outside</em> any
 * transaction - and a method calling another {@code @Transactional} method on the same object goes
 * through the {@code this} reference rather than the proxy, so the annotation would be silently
 * ignored and the entire backfill would run in whatever transaction the caller happened to have.
 * Two beans is the version of this that cannot be got wrong by accident.
 *
 * <p>Partial progress is not a problem worth guarding against. A run that dies halfway has published
 * valid events for the rows it reached, and a re-run starts from the beginning and republishes them;
 * the projection compares {@code occurredAt} against what it has stored and drops every one of them
 * as stale. The operation is idempotent by construction, so "run it again" is always the recovery.
 */
@RequiredArgsConstructor
public class SettingsBackfillBatchPublisher {

    private final UserSettingValueRepository values;
    private final UserConsentRepository consents;
    private final SettingsEventPublisher events;

    /**
     * Republishes one page of setting values.
     *
     * <p>{@code occurredAt} is the row's {@code changed_at} - the owner's own timestamp for when the
     * value last changed - and never the current time. This is the single line the whole feature
     * rests on. Stamping "now" would make every republished event look newer than whatever the
     * projection already holds, so a backfill run against an up-to-date replica would overwrite
     * current values with the owner's state as of the moment the scan happened to read each row,
     * silently undoing any change that landed mid-run.
     */
    @Transactional
    public SettingsBackfillBatch publishSettings(SettingsBackfillRequest request, UUID afterId) {
        List<UserSettingValueEntity> rows =
                values.findForBackfill(request.tenantId(), request.settingKeys(), afterId, request.batchSize());
        if (rows.isEmpty()) {
            return SettingsBackfillBatch.EMPTY;
        }
        for (UserSettingValueEntity row : rows) {
            events.publishSettingChanged(new UserSettingChangedEvent(
                    UUID.randomUUID().toString(),
                    row.getTenantId(),
                    row.getScopeType(),
                    row.getScopeId(),
                    row.getSettingKey(),
                    row.getValueText(),
                    row.getValueType(),
                    row.isRemoved(),
                    row.getChangedAt()));
        }
        return new SettingsBackfillBatch(rows.size(), rows.get(rows.size() - 1).getId());
    }

    /**
     * Republishes one page of the consent ledger.
     *
     * <p>The event carries the stored {@code consentId}, which the projection reuses as its own
     * primary key - so a decision that is already replicated collides on the key and is skipped,
     * however many times it is republished.
     */
    @Transactional
    public SettingsBackfillBatch publishConsents(SettingsBackfillRequest request, UUID afterId) {
        List<UserConsentEntity> rows =
                consents.findForBackfill(request.tenantId(), request.consentKeys(), afterId, request.batchSize());
        if (rows.isEmpty()) {
            return SettingsBackfillBatch.EMPTY;
        }
        for (UserConsentEntity row : rows) {
            events.republishConsent(new ConsentChangedEvent(
                    UUID.randomUUID().toString(),
                    row.getId(),
                    row.getTenantId(),
                    row.getSubject(),
                    row.getConsentKey(),
                    row.getTextVersion(),
                    row.getDecision(),
                    row.getLocale(),
                    row.getOccurredAt(),
                    row.getActor(),
                    row.getEvidenceIp(),
                    row.getEvidenceUserAgent(),
                    row.getCorrelationId()));
        }
        return new SettingsBackfillBatch(rows.size(), rows.get(rows.size() - 1).getId());
    }
}
