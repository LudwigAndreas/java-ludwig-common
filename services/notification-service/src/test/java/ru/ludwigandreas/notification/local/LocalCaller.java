package ru.ludwigandreas.notification.local;

import java.util.List;

/**
 * The people a local run can be called as.
 *
 * <p>A caller here is two things that have to agree: a token the stand-in issuer mints, and a row
 * in the identity projection. The token only says who the caller is. What they may do comes from the
 * projection - this service resolves authorities from its own directory, never from token claims -
 * so {@code RecipientSeed} writes each of these as a user with these roles.
 *
 * @param subject     the token's {@code sub}, and the projected user's id
 * @param displayName the token's {@code name}
 * @param email       the address the projected user is reachable at
 * @param roles       the roles the projected user holds
 */
public record LocalCaller(String subject, String displayName, String email, List<String> roles) {

    /** The tenant every seeded caller and recipient belongs to. */
    public static final String TENANT = "acme";

    /** May send, administer deliveries and publish announcements. */
    public static final LocalCaller ADMIN = new LocalCaller("local-admin", "Local Admin",
            "admin@notifications.local",
            List.of("ROLE_NOTIFICATION_ADMIN", "ROLE_NOTIFICATION_ANNOUNCER"));

    /** An ordinary user with an inbox and no roles. */
    public static final LocalCaller ADA = new LocalCaller("ada", "Ada Lovelace",
            "ada@notifications.local", List.of());

    /**
     * Every seeded caller, in the order the start-up summary lists them.
     *
     * @return the callers
     */
    public static List<LocalCaller> all() {
        return List.of(ADMIN, ADA);
    }
}
