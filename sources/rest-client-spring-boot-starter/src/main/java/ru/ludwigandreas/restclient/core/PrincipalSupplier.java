package ru.ludwigandreas.restclient.core;

/**
 * Supplies the authenticated subject an audit record attributes a call to.
 *
 * <p>Separated from {@link CallContextSource} so that the Spring Security dependency lives in one
 * conditional bean rather than in the type every pipeline holds a reference to. A service without
 * Spring Security gets no supplier and audit records carry a {@code null} principal, which is the
 * truth rather than a guess.
 */
@FunctionalInterface
public interface PrincipalSupplier {

    /** The current subject identifier, or {@code null}. Never a token, a session id or an email. */
    String principal();
}
