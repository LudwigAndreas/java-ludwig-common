package ru.ludwigandreas.pat.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.pat.audit.PatAuditEvents;
import ru.ludwigandreas.pat.config.PatProperties;
import ru.ludwigandreas.pat.entity.PatEntity;
import ru.ludwigandreas.pat.repository.PatQueryRepository;
import ru.ludwigandreas.pat.repository.PatRepository;
import ru.ludwigandreas.pat.token.PatSecret;
import ru.ludwigandreas.pat.token.PatTokens;

/**
 * The token lifecycle: issue, list, revoke, rotate, expire, purge.
 *
 * <p>Transactions are here rather than in the controller, which is the platform's convention and matters
 * particularly for rotation: that operation reads a row, mints a secret, and writes two key ids back, and a
 * failure between the mint and the write must not leave a secret handed to a caller that no row accepts.
 *
 * <h2>The secret's one appearance</h2>
 *
 * <p>{@link #issue} and {@link #rotate} are the only methods that return a secret, and they return it as a
 * {@link PatSecret} inside a result the caller must destructure. Nothing reads it back: the row holds a
 * digest, and there is no method here, on the entity, or on the repository that produces a secret from a
 * stored token. "Shown exactly once" is therefore a property of the type graph rather than a rule somebody
 * remembers - though what the <em>consuming UI</em> does with the one response is outside anything this
 * module can check, which is recorded in the change's enforcement table as one of the six conventions no
 * build can hold.
 */
@Slf4j
@RequiredArgsConstructor
public class PatService {

    private final PatRepository repository;

    private final PatQueryRepository queries;

    private final PatProperties properties;

    private final AuditSink auditSink;

    private final Clock clock;

    /**
     * What issuance and rotation return: the stored token, and the secret, once.
     *
     * @param token    the persisted entity
     * @param rendered the full wire form, to be returned to the caller in this response and never again
     */
    public record IssuedToken(PatSnapshot token, String rendered) {

        /**
         * Masked, because a record's generated {@code toString()} prints every component and one of these is
         * the credential. Easy to miss precisely because the generated version is normally what you want.
         */
        @Override
        public String toString() {
            return "IssuedToken[id=" + token.id() + ", rendered=not shown]";
        }
    }

    /**
     * Issues a token for the given owner, having checked every policy that can be checked now.
     *
     * <p>The checks here are <b>fail-fast, not enforcement</b>, and the distinction is worth keeping clear.
     * Refusing an empty scope set or a lifetime past the ceiling at issue time means the caller learns
     * immediately rather than when a pipeline breaks. But the thing that makes a token safe is the
     * <em>use-time</em> intersection in the verifying service, which runs on every request regardless of
     * what was checked here.
     *
     * @param command       what was asked for
     * @param requestedBy   who asked, which is not always the owner
     * @param ownerAuthorities the owner's current authorities, for the fail-fast scope check. Empty skips
     *                      that one check - an issuer that cannot resolve the owner's authorities should
     *                      still be able to mint, because the intersection at use time is what enforces
     */
    @Transactional
    public IssuedToken issue(IssueTokenCommand command, String requestedBy, Set<String> ownerAuthorities) {
        Instant now = clock.instant();
        validate(command, requestedBy, ownerAuthorities);

        PatTokens.MintedToken minted = PatTokens.mint();
        PatEntity entity = new PatEntity();
        // The id is deliberately NOT set here. GeneratedEntity declares it @GeneratedValue and defines
        // isNew() as `id == null`, so assigning one makes Spring Data choose merge over persist - which
        // returns a different, managed instance and leaves the one built here detached. The symptom was an
        // issued token that could not be found by its own id one line later.
        entity.setOwnerSubject(command.ownerSubject());
        entity.setName(command.name());
        entity.setKeyId(minted.keyId());
        entity.setSecretDigest(minted.digest());
        entity.setScopes(PatScopeCodec.encode(command.scopes()));
        entity.setAudiences(PatScopeCodec.encode(command.audiences()));
        entity.setAllowedCidrs(command.allowedCidrs().isEmpty()
                ? null
                : PatScopeCodec.encode(command.allowedCidrs()));
        entity.setExpiresAt(command.lifetime() == null ? null : now.plus(command.lifetime()));
        // The RETURNED instance, not the argument. For a persist they are the same object; for anything
        // Spring Data routes through merge they are not, and using the argument afterwards reads a detached
        // copy whose generated id is null.
        entity = repository.save(entity);

        auditSink.record(new PatAuditEvents.Issued(
                entity.getId().toString(),
                entity.getOwnerSubject(),
                requestedBy,
                entity.getName(),
                command.scopes(),
                command.audiences(),
                entity.getExpiresAt()).toAuditEvent());

        return new IssuedToken(PatSnapshot.of(entity, now), minted.rendered());
    }

    /**
     * Rotates a token's secret, keeping the previous one valid for the configured overlap.
     *
     * <p>The overlap is the whole point. A rotation that invalidated the old secret immediately would
     * require every consumer to be updated atomically, which nothing real can do - so the observed outcome
     * of an immediate-cutover rotation is that nobody rotates, and the credential that was supposed to be
     * refreshed quarterly is four years old.
     *
     * <p>The token's id, scopes, audiences and audit history are unchanged: this is the same token with a
     * new secret, not a new token. That is why both key ids live on one row.
     */
    @Transactional
    public IssuedToken rotate(UUID patId, String rotatedBy) {
        Instant now = clock.instant();
        PatEntity entity = require(patId);

        PatTokens.MintedToken minted = PatTokens.mint();
        // The current secret becomes the previous one, and the new one takes its place. Written in this
        // order so that a reader can see the old digest is preserved rather than dropped - getting this
        // backwards would invalidate the old secret immediately, which is the failure mode the overlap
        // exists to prevent and which no test of the happy path would catch.
        entity.setPreviousKeyId(entity.getKeyId());
        entity.setPreviousSecretDigest(entity.getSecretDigest());
        entity.setPreviousSecretExpiresAt(now.plus(properties.getRotationOverlap()));
        entity.setKeyId(minted.keyId());
        entity.setSecretDigest(minted.digest());
        entity = repository.save(entity);

        auditSink.record(new PatAuditEvents.Rotated(
                entity.getId().toString(),
                entity.getOwnerSubject(),
                rotatedBy,
                entity.getPreviousSecretExpiresAt()).toAuditEvent());

        return new IssuedToken(PatSnapshot.of(entity, now), minted.rendered());
    }

    /**
     * Revokes a token and destroys its digests.
     *
     * <p>Idempotent: revoking an already-revoked token is a no-op that returns normally rather than a
     * conflict. An operator revoking a credential during an incident may well run the command twice, and the
     * second run failing would be a worse outcome than it succeeding - the desired state is reached either
     * way, and a 409 there invites someone to go and check whether the first one worked.
     */
    @Transactional
    public void revoke(UUID patId, String revokedBy, String reason) {
        PatEntity entity = require(patId);
        if (entity.getRevokedAt() != null) {
            return;
        }
        entity.setRevokedAt(clock.instant());
        entity.setRevocationReason(reason);
        entity.destroySecrets();
        repository.save(entity);

        auditSink.record(new PatAuditEvents.Revoked(
                entity.getId().toString(), entity.getOwnerSubject(), revokedBy, reason).toAuditEvent());
    }

    /**
     * An owner's tokens, newest first. Never includes a secret, because none is stored.
     *
     * <p>Returns snapshots rather than entities, so nothing detached crosses out of this transaction. See
     * {@link PatSnapshot} for the hazard that avoids.
     */
    @Transactional(readOnly = true)
    public List<PatSnapshot> list(String ownerSubject) {
        Instant now = clock.instant();
        return queries.findByOwner(ownerSubject).stream()
                .map(entity -> PatSnapshot.of(entity, now))
                .toList();
    }

    /** One token, if it exists. */
    @Transactional(readOnly = true)
    public Optional<PatSnapshot> find(UUID patId) {
        Instant now = clock.instant();
        return repository.findById(patId).map(entity -> PatSnapshot.of(entity, now));
    }

    /**
     * Revokes every live token of an owner who has been disabled or removed.
     *
     * <p>The intersection already makes them inert - a disabled owner resolves to no authorities, so their
     * tokens confer nothing. This exists so that the token list an auditor reads matches reality rather than
     * showing live credentials for a departed employee, and so the revocation has a timestamp and a reason
     * that says which it was.
     *
     * @return how many were revoked
     */
    @Transactional
    public int revokeAllForDisabledOwner(String ownerSubject) {
        Instant now = clock.instant();
        List<PatEntity> live = queries.findLiveByOwner(ownerSubject, now);
        for (PatEntity entity : live) {
            entity.setRevokedAt(now);
            entity.setRevocationReason(RevocationReasons.OWNER_DISABLED);
            entity.destroySecrets();
            repository.save(entity);
            auditSink.record(new PatAuditEvents.Revoked(
                    entity.getId().toString(), ownerSubject, null,
                    RevocationReasons.OWNER_DISABLED).toAuditEvent());
        }
        return live.size();
    }

    /**
     * Destroys the digests of tokens whose expiry has passed, keeping the rest of the row.
     *
     * <p>An expired token already fails verification on the {@code expiresAt} check, so this is not what
     * stops it working. What it does is make the row stop being a credential at all: a digest that exists is
     * a digest that could be matched if a future change got a predicate wrong, and the record is just as
     * useful to an auditor without it.
     *
     * @return how many were handled
     */
    @Transactional
    public int destroyExpiredSecrets() {
        Instant now = clock.instant();
        List<PatEntity> expired =
                queries.findExpiredButNotYetDestroyed(now, properties.getSweepBatchSize());
        for (PatEntity entity : expired) {
            entity.destroySecrets();
            repository.save(entity);
            auditSink.record(new PatAuditEvents.Expired(
                    entity.getId().toString(), entity.getOwnerSubject(),
                    entity.getExpiresAt()).toAuditEvent());
        }
        return expired.size();
    }

    /** Drops a superseded secret once its rotation overlap has elapsed. */
    @Transactional
    public int endElapsedRotationOverlaps() {
        List<PatEntity> elapsed = queries.findWithElapsedRotationOverlap(
                clock.instant(), properties.getSweepBatchSize());
        for (PatEntity entity : elapsed) {
            entity.setPreviousKeyId(null);
            entity.setPreviousSecretDigest(null);
            entity.setPreviousSecretExpiresAt(null);
            repository.save(entity);
        }
        return elapsed.size();
    }

    /**
     * Revokes tokens that have gone unused for longer than the configured inactivity window.
     *
     * <p>A credential nobody uses is a credential nobody notices the loss of, which is the profile of one
     * pasted into a repository two years ago. Disabled by setting the window to zero.
     *
     * <p>Keys on {@code lastUsedAt} with {@code createdAt} as the fallback, because a token that has never
     * been used has no last use - and "never used in six months" is at least as strong a signal as "not used
     * recently".
     */
    @Transactional
    public int revokeInactive() {
        Duration window = properties.getInactivityExpiry();
        if (window == null || window.isZero() || window.isNegative()) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(window);
        List<PatEntity> inactive = queries.findInactive(cutoff, properties.getSweepBatchSize());
        Instant now = clock.instant();
        for (PatEntity entity : inactive) {
            entity.setRevokedAt(now);
            entity.setRevocationReason(RevocationReasons.INACTIVITY);
            entity.destroySecrets();
            repository.save(entity);
            auditSink.record(new PatAuditEvents.Revoked(
                    entity.getId().toString(), entity.getOwnerSubject(), null,
                    RevocationReasons.INACTIVITY).toAuditEvent());
        }
        return inactive.size();
    }

    /**
     * Deletes terminal records past the retention period.
     *
     * <p>Their digests were destroyed when they became terminal, so this removes an audit artifact rather
     * than a credential - which is why it is a delete rather than another soft state, and why the purge is
     * itself audited.
     */
    @Transactional
    public int purge() {
        Instant purgeBefore = clock.instant().minus(properties.getRetention());
        List<PatEntity> purgeable = queries.findPurgeable(purgeBefore, properties.getSweepBatchSize());
        for (PatEntity entity : purgeable) {
            auditSink.record(new PatAuditEvents.Purged(
                    entity.getId().toString(), entity.getOwnerSubject()).toAuditEvent());
            repository.delete(entity);
        }
        return purgeable.size();
    }

    private PatEntity require(UUID patId) {
        return repository.findById(patId).orElseThrow(PatException::notFound);
    }

    /**
     * Every policy check issuance applies, in one place.
     *
     * <p>Each refusal audits before it throws. A request to mint a credential - particularly for another
     * identity - is a security event whether it succeeds or not, and the refused ones are the interesting
     * half: a succession of refused attempts to issue a token for somebody else is a signal, and a signal
     * that only existed in a 400 response would be invisible.
     */
    private void validate(IssueTokenCommand command, String requestedBy, Set<String> ownerAuthorities) {
        if (command.scopes().isEmpty()) {
            throw refuse(command, requestedBy, "empty-scope-set", "ludwig.pat.scopes-required");
        }
        if (command.audiences().isEmpty()) {
            throw refuse(command, requestedBy, "empty-audience-set", "ludwig.pat.audiences-required");
        }
        if (command.lifetime() == null && !properties.isAllowNonExpiring()) {
            throw refuse(command, requestedBy, "no-expiry", "ludwig.pat.expiry-required");
        }
        if (command.lifetime() != null && command.lifetime().compareTo(properties.getMaxLifetime()) > 0) {
            throw refuse(command, requestedBy, "lifetime-over-ceiling", "ludwig.pat.lifetime-too-long",
                    "Requested lifetime " + command.lifetime() + " exceeds ludwig.pat.max-lifetime="
                            + properties.getMaxLifetime() + ". Refused rather than clamped: a caller who"
                            + " believes they hold a longer token finds out when their pipeline breaks.",
                    properties.getMaxLifetime());
        }
        if (!ownerAuthorities.isEmpty()) {
            Set<String> unknown = new java.util.LinkedHashSet<>(command.scopes());
            unknown.removeAll(ownerAuthorities);
            if (!unknown.isEmpty()) {
                // Fail-fast only. The token would be harmless - the use-time intersection yields nothing
                // for an authority the owner does not hold - but a caller who asked for a scope they cannot
                // grant has made a mistake, and discovering it now beats discovering it as a 403 later.
                throw refuse(command, requestedBy, "scope-not-held", "ludwig.pat.scope-not-held",
                        "Scopes not held by the owner: " + unknown + ". A token cannot grant authority its"
                                + " owner does not have, so this token would be inert.",
                        String.join(", ", unknown));
            }
        }
    }

    private PatException refuse(IssueTokenCommand command, String requestedBy, String reason,
                                 String messageKey, Object... arguments) {
        auditSink.record(new PatAuditEvents.IssuanceRefused(
                command.ownerSubject(), requestedBy, reason).toAuditEvent());
        return PatException.issuanceRefused(messageKey, arguments);
    }
}
