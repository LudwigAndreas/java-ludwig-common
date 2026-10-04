package ru.ludwigandreas.archrules.fixture.bad.credentials.catalog.issue;

import java.time.Instant;

/**
 * A single-value OAuth token holder, which is not a credential store.
 *
 * <p>The third negative, and a real type: {@code rest-client-spring-boot-starter} has one of these. It holds
 * one token with refresh-before-expiry semantics, which are not store semantics - there is no key, nothing is
 * looked up, and nothing is revoked. {@code CachingRules} makes the same observation about the same class for
 * the same reason, which is why it is worth having the fixture in both places.
 */
public class CachedToken {

    private String value;
    private Instant expiresAt;

    public String value() {
        return value;
    }

    public boolean isFresh(Instant now) {
        return expiresAt != null && expiresAt.isAfter(now);
    }
}
