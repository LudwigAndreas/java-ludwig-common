package ru.ludwigandreas.pat.service;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import ru.ludwigandreas.pat.entity.PatEntity;

/**
 * A token's visible state, decoupled from its JPA entity.
 *
 * <p>Exists because {@code architecture-rules}' {@code web.controllers-do-not-expose-entities} refused the
 * previous arrangement, in which the controller received a {@link PatEntity} and read its columns. The rule
 * was right about a real hazard rather than a stylistic one: an entity handed out of a transaction is
 * detached, so the first lazily-loaded association anybody adds to it throws at the controller - a failure
 * introduced by a change to the <em>entity</em> and surfacing in the <em>web layer</em>, which is the
 * hardest kind to attribute.
 *
 * <p>Also decodes the scope and audience sets once, here, rather than leaving the controller to call the
 * codec - which is how a second decoder appears in a web layer.
 *
 * <p>Carries <b>no digest and no key id</b>. The digests because nothing outside the verifier has any
 * business with them; the key id because it is secret-adjacent lookup material that changes on rotation,
 * so it is useless to a caller naming a token and would quietly become the identifier somebody built an
 * integration on. {@code id} survives rotation, which is what a caller and an audit record both need.
 */
public record PatSnapshot(
        UUID id,
        String ownerSubject,
        String name,
        Set<String> scopes,
        Set<String> audiences,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        String revocationReason,
        Instant lastUsedAt,
        long useCount,
        boolean live) {

    /** Projects an entity inside the transaction that loaded it. */
    public static PatSnapshot of(PatEntity entity, Instant now) {
        return new PatSnapshot(
                entity.getId(),
                entity.getOwnerSubject(),
                entity.getName(),
                PatScopeCodec.decode(entity.getScopes()),
                PatScopeCodec.decode(entity.getAudiences()),
                entity.getCreatedAt(),
                entity.getExpiresAt(),
                entity.getRevokedAt(),
                entity.getRevocationReason(),
                entity.getLastUsedAt(),
                entity.getUseCount(),
                entity.isLive(now));
    }
}
