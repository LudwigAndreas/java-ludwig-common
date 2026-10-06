package ru.ludwigandreas.pat.web;

import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.pat.service.IssueTokenCommand;
import ru.ludwigandreas.pat.service.PatException;
import ru.ludwigandreas.pat.service.PatSnapshot;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.pat.service.RevocationReasons;
import ru.ludwigandreas.pat.web.dto.IssuePatRequest;
import ru.ludwigandreas.pat.web.dto.IssuedPatResponse;
import ru.ludwigandreas.pat.web.dto.PatResponse;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.SecurityPrincipals;

/**
 * Issue, list, rotate and revoke personal access tokens.
 *
 * <p>Three layers of protection sit in front of every method here, and they are deliberately different
 * kinds of check rather than three versions of the same one:
 *
 * <ol>
 *   <li><b>Authentication</b>, from {@code security-spring-boot-starter}'s filter chain. An
 *       unauthenticated request gets that module's localized {@code 401}, not one invented here.</li>
 *   <li><b>The credential guard</b> ({@link PatCredentialGuard}), which refuses a token-backed caller
 *       before authorization is evaluated at all. A token may not mint, rotate or revoke a token.</li>
 *   <li><b>Authorization</b>, per method, distinguishing acting on your own tokens from acting on somebody
 *       else's.</li>
 * </ol>
 *
 * <p>The middle one is the one that is easy to leave out and expensive to leave out. Without it a leaked
 * read-only token is a persistence mechanism: exchange it, call this API, mint a wider one, and revoking the
 * original accomplishes nothing.
 *
 * <h2>Why the owner check is in the method body rather than only in the annotation</h2>
 *
 * <p>{@code @PreAuthorize} can compare a path variable to the authenticated name, but it cannot compare a
 * <em>field of the request body</em> - the body is not bound when the expression is evaluated. Issuance
 * takes its owner from the body, so the self-versus-other decision for that endpoint has to happen after
 * binding. The annotation therefore states the floor (you need the management permission at all) and
 * {@link #requireAuthorityOver} states the specific rule, in one place that all five endpoints call.
 *
 * <p>The alternative - an owner path variable so the annotation could do it - would mean a caller naming
 * themselves in a URL for the ordinary case, and a URL-shaped owner is a URL-shaped owner somebody will
 * eventually iterate over.
 */
@RestController
@RequiredArgsConstructor
public class PatManagementController {

    private final PatService service;

    private final Clock clock;

    @PostMapping("${ludwig.pat.web.base-path:/api/v1/personal-access-tokens}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + PatAuthorities.MANAGE_OWN + "')"
            + " or hasAuthority('" + PatAuthorities.MANAGE_ANY + "')")
    public IssuedPatResponse issue(@Valid @RequestBody IssuePatRequest request) {
        LudwigPrincipal caller = SecurityPrincipals.require();
        String owner = request.ownerOr(caller.subject());
        requireAuthorityOver(owner, caller);

        // The owner's authorities are passed only when the owner IS the caller, because that is the only
        // case where this service knows them: they are on the resolved principal. For another subject they
        // would have to be looked up, and an issuer that cannot reach the authority projection must still
        // be able to mint - the use-time intersection is what enforces, not this fail-fast.
        Set<String> ownerAuthorities = owner.equals(caller.subject())
                ? union(caller.roles(), caller.permissions())
                : Set.of();

        PatService.IssuedToken issued = service.issue(
                new IssueTokenCommand(owner, request.name(), request.scopes(), request.audiences(),
                        request.lifetime(), request.allowedCidrs()),
                caller.subject(),
                ownerAuthorities);

        return new IssuedPatResponse(render(issued.token()), issued.rendered());
    }

    /**
     * An owner's tokens.
     *
     * <p>{@code owner} is a query parameter rather than a path segment, and defaults to the caller. A
     * caller listing their own tokens sends no parameter at all, which is both the common case and the one
     * that cannot be got wrong.
     */
    @GetMapping("${ludwig.pat.web.base-path:/api/v1/personal-access-tokens}")
    @PreAuthorize("hasAuthority('" + PatAuthorities.MANAGE_OWN + "')"
            + " or hasAuthority('" + PatAuthorities.MANAGE_ANY + "')")
    public List<PatResponse> list(@RequestParam(required = false) String owner) {
        LudwigPrincipal caller = SecurityPrincipals.require();
        String subject = owner == null || owner.isBlank() ? caller.subject() : owner;
        requireAuthorityOver(subject, caller);

        return service.list(subject).stream().map(this::render).toList();
    }

    @GetMapping("${ludwig.pat.web.base-path:/api/v1/personal-access-tokens}/{id}")
    @PreAuthorize("hasAuthority('" + PatAuthorities.MANAGE_OWN + "')"
            + " or hasAuthority('" + PatAuthorities.MANAGE_ANY + "')")
    public PatResponse get(@PathVariable UUID id) {
        return render(requireOwned(id));
    }

    /**
     * Mints a new secret, keeping the previous one valid for the configured overlap.
     *
     * <p>{@code POST} to a sub-resource rather than {@code PUT} on the token: rotation is not idempotent -
     * each call produces a different secret - and a {@code PUT} that returns something new each time
     * invites a client to retry it.
     */
    @PostMapping("${ludwig.pat.web.base-path:/api/v1/personal-access-tokens}/{id}/rotations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + PatAuthorities.MANAGE_OWN + "')"
            + " or hasAuthority('" + PatAuthorities.MANAGE_ANY + "')")
    public IssuedPatResponse rotate(@PathVariable UUID id) {
        requireOwned(id);
        LudwigPrincipal caller = SecurityPrincipals.require();
        PatService.IssuedToken rotated = service.rotate(id, caller.subject());
        return new IssuedPatResponse(render(rotated.token()), rotated.rendered());
    }

    /**
     * Revokes a token.
     *
     * <p>{@code DELETE} although the row is <b>retained</b> - its digests are destroyed and everything an
     * auditor needs stays readable. {@code DELETE} is the verb a client expects for "make this credential
     * stop working", and that is what happens; the retention is an implementation of accountability rather
     * than a contradiction of the verb. The alternative, {@code POST /{id}/revocation}, would be more
     * literally accurate and would be the endpoint nobody finds during an incident.
     *
     * <p>{@code 204}, and idempotent: revoking an already-revoked token succeeds. An operator under
     * pressure may well run it twice, and the second failing would send them to check whether the first
     * worked.
     */
    @DeleteMapping("${ludwig.pat.web.base-path:/api/v1/personal-access-tokens}/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('" + PatAuthorities.MANAGE_OWN + "')"
            + " or hasAuthority('" + PatAuthorities.MANAGE_ANY + "')")
    public void revoke(@PathVariable UUID id) {
        requireOwned(id);
        LudwigPrincipal caller = SecurityPrincipals.require();
        service.revoke(id, caller.subject(), RevocationReasons.REQUESTED);
    }

    /**
     * Loads a token and refuses it unless the caller may act on its owner.
     *
     * <p>Throws the same not-found exception for "no such token" and for "somebody else's token", which is
     * deliberate: distinguishing them turns this endpoint into an oracle for whether a given token id
     * exists, and a token id appears in audit records and support tickets. A caller who may not see a token
     * learns only that they cannot see it.
     */
    private PatSnapshot requireOwned(UUID id) {
        LudwigPrincipal caller = SecurityPrincipals.require();
        PatSnapshot token = service.find(id).orElseThrow(PatException::notFound);
        if (!token.ownerSubject().equals(caller.subject())
                && !caller.hasPermission(PatAuthorities.MANAGE_ANY)) {
            throw PatException.notFound();
        }
        return token;
    }

    /**
     * Refuses a caller acting on a subject that is not themselves without the broader authority.
     *
     * <p>{@code 403} rather than a not-found, unlike {@link #requireOwned}. The difference is what the
     * caller already knows: here they supplied the subject, so telling them they may not act for it reveals
     * nothing they did not bring with them - whereas a token id they guessed would be confirmed to exist.
     */
    private void requireAuthorityOver(String owner, LudwigPrincipal caller) {
        if (!owner.equals(caller.subject()) && !caller.hasPermission(PatAuthorities.MANAGE_ANY)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Acting on another subject's personal access tokens requires the "
                            + PatAuthorities.MANAGE_ANY + " permission. Issuing a credential that acts as"
                            + " somebody else is a different act from issuing one that acts as you.");
        }
    }

    private PatResponse render(PatSnapshot token) {
        return PatResponse.of(token);
    }

    private static Set<String> union(Set<String> roles, Set<String> permissions) {
        Set<String> all = new java.util.LinkedHashSet<>(roles);
        all.addAll(permissions);
        return all;
    }
}
