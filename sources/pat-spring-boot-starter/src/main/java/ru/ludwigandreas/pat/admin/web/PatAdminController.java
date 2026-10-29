package ru.ludwigandreas.pat.admin.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.pat.admin.PatAdminOperations;
import ru.ludwigandreas.pat.admin.service.PatAdminService;
import ru.ludwigandreas.pat.service.IssueTokenCommand;
import ru.ludwigandreas.pat.service.PatService;
import ru.ludwigandreas.pat.service.PatSnapshot;
import ru.ludwigandreas.pat.web.dto.IssuedPatResponse;
import ru.ludwigandreas.pat.web.dto.PatResponse;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminPage;

/**
 * The {@code pat} administrative area: any owner's tokens.
 *
 * <p>A personal access token is refused here by {@code PatCredentialGuard}, which this module registers for these
 * paths too, whatever {@code ludwig.admin.allow-personal-access-tokens} says: a token may not mint, rotate or
 * revoke a token.
 */
@RestController
@RequestMapping("/admin/v1/pat/tokens")
public class PatAdminController {

    private final PatAdminService service;

    /**
     * Creates the controller.
     *
     * @param service the area's operations
     */
    public PatAdminController(PatAdminService service) {
        this.service = service;
    }

    /**
     * Tokens matching the filter, newest first; metadata only.
     *
     * @param filter an OData {@code $filter} over the token's filterable fields
     * @param cursor the previous page's cursor
     * @param size   the page size, at most 200
     * @return one page
     */
    @GetMapping
    @AdminAccess(PatAdminOperations.READ)
    public AdminPage<PatResponse> tokens(@RequestParam(name = "$filter", required = false) String filter,
                                         @RequestParam(required = false) String cursor,
                                         @RequestParam(required = false) Integer size) {
        AdminPage<PatSnapshot> page = service.tokens(filter, cursor, size);
        return new AdminPage<>(page.items().stream().map(PatResponse::of).toList(), page.next());
    }

    /**
     * One token's metadata.
     *
     * @param id the token
     * @return the token
     */
    @GetMapping("/{id}")
    @AdminAccess(PatAdminOperations.READ)
    public PatResponse token(@PathVariable UUID id) {
        return PatResponse.of(service.token(id));
    }

    /**
     * Issues a token for another owner; the secret is in this response and nowhere else.
     *
     * @param request what to issue, and for whom
     * @return the token and its secret
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @AdminAccess(PatAdminOperations.PRIVILEGED)
    public IssuedPatResponse issue(@Valid @RequestBody IssueForOwnerRequest request) {
        PatService.IssuedToken issued = service.issue(new IssueTokenCommand(request.owner(), request.name(),
                request.scopes(), request.audiences(), request.lifetime(), request.allowedCidrs()));
        return new IssuedPatResponse(PatResponse.of(issued.token()), issued.rendered());
    }

    /**
     * Revokes anybody's token. Idempotent, as the owner's revocation is.
     *
     * @param id the token
     * @return the token as it now stands
     */
    @PostMapping("/{id}/revoke")
    @AdminAccess(PatAdminOperations.PRIVILEGED)
    public PatResponse revoke(@PathVariable UUID id) {
        return PatResponse.of(service.revoke(id));
    }

    /**
     * An issuance for a named owner. The self-service request's shape with the owner required.
     *
     * @param owner        whom the token acts as
     * @param name         its name
     * @param scopes       the authorities it may carry
     * @param audiences    the services it may be exchanged for
     * @param lifetime     how long it lives
     * @param allowedCidrs where it may be exchanged from
     */
    public record IssueForOwnerRequest(@NotBlank String owner, @NotBlank @Size(max = 200) String name,
                                       @NotEmpty Set<String> scopes, @NotEmpty Set<String> audiences,
                                       Duration lifetime, List<String> allowedCidrs) {
    }
}
