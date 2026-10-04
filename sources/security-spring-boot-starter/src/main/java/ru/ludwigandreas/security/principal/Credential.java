package ru.ludwigandreas.security.principal;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/**
 * What was presented on this request, and which long-lived credential it came from if any.
 *
 * <p>Lives on {@link LudwigAuthentication} - the per-request authentication - and deliberately not on
 * {@link LudwigPrincipal}. The principal is the identity model: who the caller is, normalized once at the
 * edge of the service so that nothing downstream has to know which door they came through. A credential is a
 * property of <em>how this particular request authenticated</em>, which is not part of who anybody is. The
 * same person is the same principal whether they presented a session-derived assertion or a token-derived
 * one.
 *
 * <p>An earlier design put this on the principal as a new record component and accepted that it broke the
 * canonical constructor for every consumer. That was the path of least resistance to exactly the category
 * error above, and correcting it also removed the only breaking change in the work.
 *
 * @param kind what was presented
 * @param id   the long-lived credential's stable id when there is one - a token id, never a key id and never
 *             anything secret-adjacent. Empty for {@link CredentialKind#DIRECT}. The id is what an audit
 *             record names and what an operator revokes; it is not material an attacker gains anything from,
 *             which is why it may sit in a log line that the secret may not.
 */
public record Credential(CredentialKind kind, String id) implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The ordinary case: the caller presented whatever established their identity. */
    public static final Credential DIRECT = new Credential(CredentialKind.DIRECT, null);

    public Credential {
        Objects.requireNonNull(kind, "kind");
        if (kind != CredentialKind.DIRECT && (id == null || id.isBlank())) {
            // A token-backed request with no token id is unauditable: the record would say the request was
            // token-backed and give nobody a way to find which token, which is worse than either of the two
            // consistent states. Refused at construction rather than discovered during an investigation.
            throw new IllegalArgumentException(
                    "a " + kind + " credential must carry the id of the credential presented");
        }
        id = id == null || id.isBlank() ? null : id;
    }

    /** A personal access token, named by its stable id. */
    public static Credential personalAccessToken(String patId) {
        return new Credential(CredentialKind.PERSONAL_ACCESS_TOKEN, patId);
    }

    /** The presented credential's id, when it has one. */
    public Optional<String> credentialId() {
        return Optional.ofNullable(id);
    }

    /** Whether this is the given kind, so a policy check reads as a question rather than a comparison. */
    public boolean isKind(CredentialKind expected) {
        return kind == expected;
    }

    /**
     * Whether a long-lived credential was presented.
     *
     * <p>Phrased as "not direct" rather than enumerating the kinds, so that a second long-lived credential -
     * a deploy key, a signed webhook secret - is covered by every policy written against this the day it is
     * added. A check written as {@code isKind(PERSONAL_ACCESS_TOKEN)} would silently stop covering the
     * management surface when the second kind arrived, and that is the kind of gap nobody notices.
     */
    public boolean isLongLived() {
        return kind != CredentialKind.DIRECT;
    }
}
