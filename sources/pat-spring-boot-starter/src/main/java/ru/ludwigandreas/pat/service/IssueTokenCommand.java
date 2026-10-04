package ru.ludwigandreas.pat.service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * What a caller asks for when issuing a token.
 *
 * <p>A command object rather than a six-argument method, and the reason is the two subjects. Issuance has
 * both an <b>owner</b> (whose authority the token attenuates) and a <b>requester</b> (who asked), and they
 * are the same person in the common case and different in the one that needs an extra authority. Two
 * adjacent {@code String} parameters that are usually equal is a signature where transposing them compiles,
 * passes the common-case test, and mints tokens for the wrong person.
 *
 * @param ownerSubject whose live authority this token will attenuate
 * @param name         the owner's label for it
 * @param scopes       the attenuation. Non-empty, checked against the owner's current authorities at issue
 *                     time as a fail-fast - the enforcement is the use-time intersection
 * @param audiences    where it may be presented. Non-empty, with no default
 * @param lifetime     how long it lives. {@code null} means non-expiring, which is refused unless the
 *                     deployment has explicitly allowed it
 * @param allowedCidrs optional source restriction; empty means from anywhere
 */
public record IssueTokenCommand(
        String ownerSubject,
        String name,
        Set<String> scopes,
        Set<String> audiences,
        Duration lifetime,
        List<String> allowedCidrs) {

    public IssueTokenCommand {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        audiences = audiences == null ? Set.of() : Set.copyOf(audiences);
        allowedCidrs = allowedCidrs == null ? List.of() : List.copyOf(allowedCidrs);
    }
}
