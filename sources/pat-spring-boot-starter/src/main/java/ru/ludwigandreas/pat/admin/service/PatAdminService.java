package ru.ludwigandreas.pat.admin.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.pat.repository.PatQueryRepository;
import ru.ludwigandreas.pat.service.IssueTokenCommand;
import ru.ludwigandreas.pat.service.PatException;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.pat.service.PatSnapshot;
import ru.ludwigandreas.pat.service.RevocationReasons;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminCursor;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.admin.AdminOutcome;
import ru.ludwigandreas.security.admin.AdminPage;

/**
 * The {@code pat} area's operations: any owner's tokens, issuance for another owner and revocation of anybody's,
 * through the same {@link PatService} the self-service API uses, so scope, expiry, lifetime and the revocation
 * window are the same.
 */
public class PatAdminService {

    private static final String RESOURCE = "personal-access-token";

    private final PatService tokens;
    private final PatQueryRepository queries;
    private final AdminAudit audit;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param tokens  the token lifecycle
     * @param queries the token table's queries
     * @param audit   the administrative audit
     * @param clock   the clock expiry is judged against
     */
    public PatAdminService(PatService tokens, PatQueryRepository queries, AdminAudit audit, Clock clock) {
        this.tokens = tokens;
        this.queries = queries;
        this.audit = audit;
        this.clock = clock;
    }

    /** A token's state, as the administrative listing filters on it. */
    public enum TokenStatus {
        /** Neither revoked nor past its expiry. */
        ACTIVE,
        /** Past its expiry and not revoked. */
        EXPIRED,
        /** Revoked. */
        REVOKED
    }

    /**
     * One page of tokens, newest first.
     *
     * @param owner  the owner, or null
     * @param status the state, or null
     * @param cursor the previous page's cursor, or null
     * @param size   the page size, or null
     * @return the page
     */
    @Transactional(readOnly = true)
    public AdminPage<PatSnapshot> tokens(String owner, TokenStatus status, String cursor, Integer size) {
        int pageSize = AdminCursor.pageSize(size);
        Instant afterCreatedAt = null;
        UUID afterId = null;
        if (cursor != null) {
            List<String> position = AdminCursor.decode(cursor, 2);
            afterCreatedAt = Instant.parse(position.get(0));
            afterId = UUID.fromString(position.get(1));
        }
        Boolean revoked = status == null ? null : status == TokenStatus.REVOKED;
        Boolean expired = status == null || status == TokenStatus.REVOKED ? null : status == TokenStatus.EXPIRED;
        Instant now = clock.instant();
        List<PatSnapshot> rows = queries.findForAdministration(owner, revoked, expired, now, afterCreatedAt,
                        afterId, pageSize + 1)
                .stream().map(entity -> PatSnapshot.of(entity, now)).toList();
        return AdminPage.of(rows, pageSize, row -> AdminCursor.encode(row.createdAt().toString(),
                row.id().toString()));
    }

    /**
     * One token.
     *
     * @param id the token
     * @return its metadata
     */
    public PatSnapshot token(UUID id) {
        return tokens.find(id).orElseThrow(PatException::notFound);
    }

    /**
     * Issues a token for another owner. The owner's authorities are not known here, so the issue-time scope check
     * is skipped and the use-time intersection is what bounds the token, as for any issuance on another's behalf.
     *
     * @param command what was asked for
     * @return the token and its secret, returned this once
     */
    public PatService.IssuedToken issue(IssueTokenCommand command) {
        String caller = AdminInvocation.require().principal().subject();
        PatService.IssuedToken issued = tokens.issue(command, caller, Set.of());
        audit.record(RESOURCE, issued.token().id().toString(), AdminOutcome.SUCCEEDED,
                Map.of("owner", command.ownerSubject()));
        return issued;
    }

    /**
     * Revokes anybody's token, with the same revocation window as an owner's revocation.
     *
     * @param id the token
     * @return the token as it now stands
     */
    public PatSnapshot revoke(UUID id) {
        PatSnapshot before = token(id);
        tokens.revoke(id, AdminInvocation.require().principal().subject(), RevocationReasons.REQUESTED);
        audit.record(RESOURCE, id.toString(),
                before.revokedAt() == null ? AdminOutcome.SUCCEEDED : AdminOutcome.REFUSED_BY_STATE,
                Map.of("owner", before.ownerSubject()));
        return token(id);
    }
}
