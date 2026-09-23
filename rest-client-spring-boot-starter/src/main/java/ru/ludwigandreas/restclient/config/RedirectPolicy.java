package ru.ludwigandreas.restclient.config;

/** What a named client does with a 3xx that carries a {@code Location}. */
public enum RedirectPolicy {

    /**
     * Follow redirects, except a redirect from HTTPS to plain HTTP. The default, and the JDK's own
     * {@code NORMAL}: a downgrade to cleartext is how a credential ends up on the wire in the clear,
     * and no legitimate internal service asks for one.
     */
    NORMAL,

    /** Never follow; the 3xx is returned to the caller as-is. */
    NEVER,

    /** Follow every redirect, including a downgrade to HTTP. Requires a deliberate choice. */
    ALWAYS
}
