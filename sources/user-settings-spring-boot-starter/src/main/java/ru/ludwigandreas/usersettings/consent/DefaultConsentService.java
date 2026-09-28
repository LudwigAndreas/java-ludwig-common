package ru.ludwigandreas.usersettings.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.audit.SettingsCorrelationIdProvider;
import ru.ludwigandreas.usersettings.api.ConsentDecision;
import ru.ludwigandreas.usersettings.entity.UserConsentEntity;
import ru.ludwigandreas.usersettings.event.ConsentChangedEvent;
import ru.ludwigandreas.usersettings.event.SettingsEventPublisher;
import ru.ludwigandreas.usersettings.exception.ReadOnlySettingsException;
import ru.ludwigandreas.usersettings.metrics.SettingsMetrics;
import ru.ludwigandreas.usersettings.repository.UserConsentRepository;
import ru.ludwigandreas.usersettings.resolve.SettingsAccessPolicy;
import ru.ludwigandreas.usersettings.resolve.SettingsSubjects;
import ru.ludwigandreas.usersettings.resolve.SettingsTenantResolver;

/**
 * The owner-mode consent ledger.
 *
 * <p>Every write is an insert and there is no update path anywhere in this class - not as a matter
 * of discipline but because {@code UserConsentEntity} is a {@code SnapshotEntity} and db-core's
 * listener rejects any update, with a database trigger behind that in case something bypasses the
 * ORM entirely. Three layers of the same rule, because "we never update consents" is a claim made to
 * auditors and one enforced only by convention is not worth making.
 *
 * <p>Current state is folded from the history on every read rather than cached. Consents are read
 * far less often than settings and change even less, and a cache here would be a second copy of the
 * one thing in this module that must not have two copies.
 */
@RequiredArgsConstructor
public class DefaultConsentService implements ConsentService {

    /** {@code sourceSystem} for a decision this deployment witnessed itself. */
    public static final String OWN_SOURCE = "self";

    private final UserConsentRepository repository;
    private final ConsentEntityMapper mapper;
    private final SettingsAccessPolicy accessPolicy;
    private final SettingsTenantResolver tenantResolver;
    private final SettingsEventPublisher events;
    private final SettingsCorrelationIdProvider correlation;
    private final SettingsMetrics metrics;
    private final Clock clock;

    /**
     * False in projection mode, where this service serves the read half of the same ledger.
     *
     * <p>A flag rather than a second implementation: reading a projected consent is exactly the same
     * query against exactly the same table, and duplicating the class to change two methods into
     * refusals is how the two copies come to disagree about what "current state" means.
     */
    private final boolean writable;

    @Override
    @Transactional
    public ConsentRecord grant(PrincipalRef ref, ConsentGrant grant) {
        return record(ref, grant, ConsentDecision.GRANTED);
    }

    @Override
    @Transactional
    public ConsentRecord revoke(PrincipalRef ref, ConsentGrant grant) {
        return record(ref, grant, ConsentDecision.REVOKED);
    }

    @Override
    @Transactional(readOnly = true)
    public ConsentState currentState(PrincipalRef ref) {
        return currentState(subjectFor(ref));
    }

    @Override
    @Transactional(readOnly = true)
    public ConsentState currentState(SettingsSubject subject) {
        return stateAsOf(subject, null);
    }

    @Override
    @Transactional(readOnly = true)
    public ConsentState stateAsOf(PrincipalRef ref, Instant asOf) {
        return stateAsOf(subjectFor(ref), asOf);
    }

    @Override
    @Transactional(readOnly = true)
    public ConsentState stateAsOf(SettingsSubject subject, Instant asOf) {
        accessPolicy.check(subject);
        return fold(repository.history(subject.tenantId(), subject.subject(), null, asOf));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsentRecord> history(PrincipalRef ref, String consentKey) {
        return history(subjectFor(ref), consentKey);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsentRecord> history(SettingsSubject subject, String consentKey) {
        accessPolicy.check(subject);
        return mapper.toRecords(repository.history(subject.tenantId(), subject.subject(), consentKey, null));
    }

    private ConsentRecord record(PrincipalRef ref, ConsentGrant grant, ConsentDecision decision) {
        if (!writable) {
            // Accepting the decision locally would produce evidence the owner does not have, which the
            // next projected event would not correct because nothing would ever contradict it.
            throw new ReadOnlySettingsException();
        }
        SettingsSubject subject = subjectFor(ref);
        accessPolicy.check(subject);

        UserConsentEntity entity = new UserConsentEntity();
        // Assigned rather than generated: every projection of this decision reuses the id verbatim,
        // which is what makes a redelivered event collide instead of appending a duplicate.
        entity.setId(UUID.randomUUID());
        entity.setSourceSystem(OWN_SOURCE);
        entity.setTenantId(subject.tenantId());
        entity.setSubject(subject.subject());
        entity.setConsentKey(grant.consentKey());
        entity.setTextVersion(grant.textVersion());
        entity.setDecision(decision);
        entity.setLocale(grant.locale());
        entity.setOccurredAt(grant.occurredAt() == null ? clock.instant() : grant.occurredAt());
        entity.setActor(SecurityPrincipals.currentSubject().orElse(subject.subject()));
        entity.setEvidenceIp(grant.evidenceIp());
        entity.setEvidenceUserAgent(grant.evidenceUserAgent());
        correlation.currentCorrelationId().ifPresent(entity::setCorrelationId);
        entity.setSourceTimestamp(entity.getOccurredAt());

        UserConsentEntity saved = repository.save(entity);
        events.publishConsentChanged(toEvent(saved));
        metrics.recordConsentDecision(grant.consentKey(), decision.name());
        return mapper.toRecord(saved);
    }

    /**
     * The ledger arrives oldest first, so overwriting per key leaves the most recent decision - which
     * is why the repository sorts by {@code occurredAt} and then by {@code importedAt} rather than by
     * {@code occurredAt} alone. Two decisions sharing an instant would otherwise fold in whatever
     * order the database returned them.
     */
    private ConsentState fold(List<UserConsentEntity> history) {
        Map<String, ConsentRecord> latest = new LinkedHashMap<>();
        for (UserConsentEntity entity : history) {
            latest.put(entity.getConsentKey(), mapper.toRecord(entity));
        }
        return new ConsentState(latest);
    }

    private ConsentChangedEvent toEvent(UserConsentEntity entity) {
        return new ConsentChangedEvent(
                UUID.randomUUID().toString(),
                entity.getId(),
                entity.getTenantId(),
                entity.getSubject(),
                entity.getConsentKey(),
                entity.getTextVersion(),
                entity.getDecision(),
                entity.getLocale(),
                entity.getOccurredAt(),
                entity.getActor(),
                entity.getEvidenceIp(),
                entity.getEvidenceUserAgent(),
                entity.getCorrelationId());
    }

    private SettingsSubject subjectFor(PrincipalRef ref) {
        return SettingsSubjects.require(tenantResolver, ref);
    }
}
