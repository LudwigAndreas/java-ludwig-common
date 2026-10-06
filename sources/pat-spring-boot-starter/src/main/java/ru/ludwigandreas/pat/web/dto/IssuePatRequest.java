package ru.ludwigandreas.pat.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * What a caller posts to issue a token.
 *
 * <p>{@code owner} is optional and defaults to the caller: self-service is the common case and should not
 * require naming yourself. Supplying somebody else's subject is what requires
 * {@link ru.ludwigandreas.pat.web.PatAuthorities#MANAGE_ANY}.
 *
 * <p>{@code scopes} and {@code audiences} are both {@code @NotEmpty}, and the audience one is the easy
 * requirement to think is over-strict. It is not: every available default is either "every service this
 * token permits" or "everything", and both are the condition audience binding exists to prevent. Scope
 * answers <em>what</em> may be done; audience answers <em>where</em>.
 *
 * <p>{@code lifetime} is a {@link Duration} rather than an absolute instant on purpose. A caller asking for
 * "90 days" means 90 days from issuance; a caller supplying a timestamp has to reason about clock skew
 * between their machine and the issuer, and the failure mode is a token that is already expired or lives
 * slightly too long. A null lifetime means non-expiring and is refused unless the deployment has explicitly
 * allowed it.
 */
public record IssuePatRequest(
        String owner,

        @NotBlank
        @Size(max = 200)
        String name,

        @NotEmpty
        Set<String> scopes,

        @NotEmpty
        Set<String> audiences,

        Duration lifetime,

        List<String> allowedCidrs) {

    public IssuePatRequest {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        audiences = audiences == null ? Set.of() : Set.copyOf(audiences);
        allowedCidrs = allowedCidrs == null ? List.of() : List.copyOf(allowedCidrs);
    }

    /** The owner this request names, or the caller when it names nobody. */
    public String ownerOr(String caller) {
        return owner == null || owner.isBlank() ? caller : owner;
    }
}
