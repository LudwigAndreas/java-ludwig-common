package ru.ludwigandreas.pat.introspection;

/**
 * The names the introspection request uses, defined once.
 *
 * <p>A handful of constants rather than a request record, because the request is one form field and a
 * record for it would be a type whose only purpose is to be flattened again. What matters is that the
 * field name and the default path are not spelled out independently on both sides of the call.
 */
public final class PatIntrospection {

    /** The form field carrying the token, as RFC 7662 names it. */
    public static final String TOKEN_PARAMETER = "token";

    /**
     * The default path the endpoint is mounted at.
     *
     * <p>Not under {@code /api/v1}, deliberately: {@code /introspect} is where RFC 7662 puts it and where
     * a consumer configured against the standard looks. Versioning somebody else's standard would be the
     * mistake. The architecture rule that wants a versioned base path is disabled for the same reason on
     * the exchange endpoint, with the reason recorded there.
     */
    public static final String DEFAULT_PATH = "/introspect";

    private PatIntrospection() {
    }
}
