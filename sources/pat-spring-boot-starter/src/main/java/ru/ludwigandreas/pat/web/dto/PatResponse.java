package ru.ludwigandreas.pat.web.dto;

import java.time.Instant;
import java.util.Set;
import ru.ludwigandreas.pat.service.PatSnapshot;

/**
 * A token as a caller may see it.
 *
 * <p><b>There is no secret field and no digest field, and that is structural rather than careful.</b> This
 * record cannot carry one: {@link #of} reads the entity and never touches {@code secretDigest}, and no
 * caller can add a component to a record at a call site. "The secret is shown exactly once" therefore holds
 * for every read endpoint by construction, and the only type that carries a secret at all is
 * {@link IssuedPatResponse}, which exists solely for the one response that may.
 *
 * <p>The {@code keyId} is also absent. It is not confidential, but it is secret-adjacent lookup material
 * that changes on rotation - so it is useless to a caller who wants to name a token and would quietly become
 * the identifier somebody built an integration on. {@code id} survives rotation, which is what a caller and
 * an audit record both need.
 *
 * <p>{@code audiences} is included. Withholding it would be false secrecy: the exchange refuses a wrong
 * audience without saying so, because telling an <em>attacker</em> which services a harvested token reaches
 * is a gift - but the legitimate owner needs to know where their own token works, and they already hold it.
 */
public record PatResponse(
        String id,
        String owner,
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

    /**
     * Projects an entity.
     *
     * <p>Takes a {@link PatSnapshot} rather than the entity. The entity version was refused by
     * {@code web.controllers-do-not-expose-entities}, correctly: a detached entity in a web layer throws on
     * the first lazily-loaded association anybody adds to it, which is a failure introduced by a change to
     * the entity and surfacing in the controller.
     *
     * <p>A static factory rather than a MapStruct mapper, which is what the three existing starters with a
     * web surface do - {@code ReportDefinitionResponse.of(...)} in export, and the renderer in
     * user-settings. MapStruct earns its place where two rich types are mapped field-for-field; here the
     * interesting part of the mapping is what is deliberately <em>not</em> copied, and a generated mapper
     * expresses omission as an {@code @Mapping(ignore = true)} that reads as an afterthought rather than as
     * the point.
     */
    public static PatResponse of(PatSnapshot token) {
        return new PatResponse(
                token.id().toString(),
                token.ownerSubject(),
                token.name(),
                token.scopes(),
                token.audiences(),
                token.createdAt(),
                token.expiresAt(),
                token.revokedAt(),
                token.revocationReason(),
                token.lastUsedAt(),
                token.useCount(),
                token.live());
    }
}
