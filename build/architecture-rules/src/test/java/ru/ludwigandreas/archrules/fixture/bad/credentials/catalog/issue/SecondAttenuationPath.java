package ru.ludwigandreas.archrules.fixture.bad.credentials.catalog.issue;

import java.util.Set;

/**
 * A second place that decides what a credential is worth.
 *
 * <p>The shape {@code credentials.one-attenuation-path} exists to catch, and the reason the rule names a
 * single type rather than a package. Note how reasonable it looks: a filter authenticating a credential
 * needs an authentication object, so it builds one, and while it is there it works out the effective
 * authority. The {@code addAll} is the whole defect and it is one word away from being correct.
 *
 * <p>This fixture cannot reference the real {@code LudwigAuthentication} - the rule library is a bytecode
 * analyser with no dependency on the security starter, which is why {@code CredentialRulesTest} records
 * that the three type-dependent rules are proven not to misfire here and proven to fire in the modules
 * that actually contain their subjects. What this fixture demonstrates is the <em>shape</em>, so a reader
 * of the rule can see what it is looking for.
 */
public class SecondAttenuationPath {

    /**
     * Computes authority outside the one construction site.
     *
     * <p>A union rather than an intersection, which is the mistake. A token's scopes must <b>narrow</b>
     * its owner's live authority and never add to it; this grants the caller everything the token names
     * whether or not its owner has it, which turns an attenuation into a privilege grant with a
     * ninety-day lifetime.
     */
    public Set<String> effectiveAuthority(Set<String> ownerAuthorities, Set<String> tokenScopes) {
        Set<String> effective = new java.util.LinkedHashSet<>(ownerAuthorities);
        effective.addAll(tokenScopes);
        return effective;
    }
}
