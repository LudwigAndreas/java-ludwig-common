package ru.ludwigandreas.pat.web.dto;

/**
 * The one response that carries a secret.
 *
 * <p>A separate type from {@link PatResponse} rather than a nullable field on it, and the separation is the
 * mechanism rather than a style preference. A nullable {@code secret} on the shared response type would mean
 * every list and every read endpoint returns a shape that <em>could</em> carry a secret, and whether it does
 * depends on a code path somewhere being right. Two types means the read endpoints return a type with no
 * such field, so they cannot leak one however they are changed.
 *
 * <p>Returned by issuance and rotation only. Nothing can produce this from a stored token: the row holds a
 * digest, and the secret exists between the generator and this response.
 *
 * @param token  the token's visible state
 * @param secret the full credential, in the only response that will ever contain it
 */
public record IssuedPatResponse(PatResponse token, String secret) {

    /**
     * Masked.
     *
     * <p>A record's generated {@code toString()} prints every component, and one of these is the credential.
     * Easy to miss precisely because the generated version is normally what you want - and this object sits
     * in a controller return value, where an interceptor, a logging advice or a debugger will print it.
     */
    @Override
    public String toString() {
        return "IssuedPatResponse[token=" + token.id() + ", secret=not shown]";
    }
}
