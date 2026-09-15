package ru.ludwigandreas.security.authz;

import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * The lookup key an {@link AuthorityResolver} resolves and an {@link AuthorityCache} is keyed on.
 *
 * <p>Type is part of the key, not decoration: subject namespaces are independent, and a partner id
 * that happens to equal some user's {@code sub} must never collide into the same cache entry and
 * hand one caller the other's roles.
 *
 * @param type    which door the caller came through
 * @param subject the caller's stable id within that namespace
 */
public record PrincipalRef(PrincipalType type, String subject) {

    public PrincipalRef {
        if (type == null) {
            throw new IllegalArgumentException("principal type must not be null");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("principal subject must not be blank");
        }
    }

    public static PrincipalRef user(String subject) {
        return new PrincipalRef(PrincipalType.USER, subject);
    }

    public static PrincipalRef partner(String partnerId) {
        return new PrincipalRef(PrincipalType.PARTNER, partnerId);
    }

    public static PrincipalRef service(String workloadId) {
        return new PrincipalRef(PrincipalType.SERVICE, workloadId);
    }
}
