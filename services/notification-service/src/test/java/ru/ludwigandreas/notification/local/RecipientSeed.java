package ru.ludwigandreas.notification.local;

import java.time.Instant;
import java.util.Set;
import ru.ludwigandreas.identity.feed.IdentityChange;
import ru.ludwigandreas.identity.feed.IdentityProjection;
import ru.ludwigandreas.identity.feed.UserProfile;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixture;

/**
 * The people in the directory: the callers a developer acts as, and one recipient per shape the
 * delivery logic treats differently.
 *
 * <p>Written through the identity projection's change feed, as changes of the local profile's own
 * {@code local} source - the same path a deployment's identity provider feeds.
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

    /** The seed's source, declared by the local-run profile. */
    public static final String SOURCE = "local";

    /** A fixed time, so a second start applies nothing: every change is then stale. */
    private static final Instant SEEDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private final IdentityProjection projection;

    public RecipientSeed(IdentityProjection projection) {
        this.projection = projection;
    }

    @Override
    public void seed() {
        for (LocalCaller caller : LocalCaller.all()) {
            projection.apply(upsert(caller.subject(), caller.displayName(), caller.email(), Set.copyOf(caller.roles()),
                    caller == LocalCaller.ADA ? ADA_CHAT_HANDLE : null));
        }
        projection.apply(upsert(EMAIL_ONLY, EMAIL_ONLY, EMAIL_ONLY_ADDRESS, Set.of(), null));
        projection.apply(upsert(DEACTIVATED, DEACTIVATED, "dennis@notifications.local", Set.of(), null));
        projection.apply(new IdentityChange.Disable(SOURCE, DEACTIVATED, SEEDED_AT.plusSeconds(1), null));
    }

    private static IdentityChange upsert(String subject, String displayName, String email, Set<String> roles,
                                         String chatHandle) {
        return new IdentityChange.Upsert(SOURCE, subject, SEEDED_AT, null, new UserProfile(displayName,
                LocalCaller.TENANT, roles, new UserProfile.Contact(email, true, null, null, null, chatHandle)));
    }
}
