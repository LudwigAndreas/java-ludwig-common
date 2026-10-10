package ru.ludwigandreas.notification.local;

import java.time.Instant;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.entity.UserStatus;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixture;

/**
 * The people in the directory: the callers a developer acts as, and one recipient per shape the
 * delivery logic treats differently.
 *
 * <p>Written through the identity projection's own repository - the table a deployment fills from
 * the identity provider's change stream.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p><b>No recipient with an opt-out or quiet hours.</b> Those are stored preferences, and this
 * service does not have a preference store yet: {@code user-settings-spring-boot-starter} is a
 * {@code provided} dependency that the Spring Boot plugin excludes from what runs, so a deployment -
 * and {@code spring-boot:test-run} - resolves every recipient's preferences from configured defaults.
 * Seeding rows the running service cannot read would be seeding a lie. (The integration tests can,
 * because the starter is on the test classpath; see {@code RecipientFixtures}.)
 *
 * <p><b>No "webhook recipient".</b> A webhook has no per-user address in this service - a request
 * that wants one names the URL as an address recipient - so the webhook stand-in's URL is printed in
 * the start-up summary instead of being stored here.
 */
public class RecipientSeed implements SeedFixture {

    /** Reachable by email only. */
    public static final String EMAIL_ONLY = "grace";

    /** The address of {@link #EMAIL_ONLY}. */
    public static final String EMAIL_ONLY_ADDRESS = "grace@notifications.local";

    /** Deactivated in the directory, so nothing is sent to them. */
    public static final String DEACTIVATED = "dennis";

    /** The chat handle of {@link LocalCaller#ADA}, who is reachable by email and chat. */
    public static final String ADA_CHAT_HANDLE = "@ada";

    private final SecurityUserRepository users;

    public RecipientSeed(SecurityUserRepository users) {
        this.users = users;
    }

    @Override
    public void seed() {
        for (LocalCaller caller : LocalCaller.all()) {
            SecurityUserEntity user = active(caller.subject(), caller.email());
            user.setDisplayName(caller.displayName());
            user.getRoles().addAll(caller.roles());
            if (caller == LocalCaller.ADA) {
                user.setChatHandle(ADA_CHAT_HANDLE);
            }
            users.save(user);
        }
        users.save(active(EMAIL_ONLY, EMAIL_ONLY_ADDRESS));
        SecurityUserEntity deactivated = active(DEACTIVATED, "dennis@notifications.local");
        deactivated.setStatus(UserStatus.DISABLED);
        users.save(deactivated);
    }

    private static SecurityUserEntity active(String id, String email) {
        SecurityUserEntity user = new SecurityUserEntity();
        user.setId(id);
        user.setDisplayName(id);
        user.setTenantId(LocalCaller.TENANT);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmail(email);
        user.setEmailVerified(true);
        user.setSourceSystem("local-seed");
        user.setSourceTimestamp(Instant.now());
        return user;
    }
}
