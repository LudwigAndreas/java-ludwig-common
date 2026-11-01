package ru.ludwigandreas.identity.feed;

import java.util.Set;

/**
 * What a source knows about a user. Every field may be null, meaning the source did not say: the projection keeps
 * the stored value, because sources send partial profiles and a null-overwriting merge would erase a tenant on an
 * unrelated update.
 *
 * @param displayName human-readable label, stored for audit readability
 * @param tenantId    owning organisation, in a multi-tenant deployment
 * @param roles       the user's <em>complete</em> role set as the source names it, mapped by the source's role
 *                    mapping; empty withdraws every role, null keeps the stored roles
 * @param contact     contact data, stored only where {@code ludwig.identity.contact.enabled} is set
 */
public record UserProfile(String displayName, String tenantId, Set<String> roles, Contact contact) {

    public UserProfile {
        roles = roles == null ? null : Set.copyOf(roles);
    }

    /**
     * A user's contact data. A verification flag is {@code Boolean}: null means the source did not say, which differs
     * from "not verified".
     *
     * @param email          the primary address
     * @param emailVerified  whether the source verified {@code email}
     * @param alternateEmail a secondary address
     * @param phoneNumber    the phone number in E.164 form
     * @param phoneVerified  whether the source verified {@code phoneNumber}
     * @param chatHandle     the handle on the organisation's chat system
     */
    public record Contact(String email, Boolean emailVerified, String alternateEmail, String phoneNumber,
                          Boolean phoneVerified, String chatHandle) {
    }
}
