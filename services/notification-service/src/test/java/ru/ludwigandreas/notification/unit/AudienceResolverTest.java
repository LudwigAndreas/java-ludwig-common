package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.notification.service.announcement.AnnouncementAudienceResolver;
import ru.ludwigandreas.notification.service.exception.AudienceNotPermittedException;
import ru.ludwigandreas.notification.service.model.Audience;
import ru.ludwigandreas.notification.service.model.AudienceType;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * The allowlist, which is the only thing standing between "publish an announcement" and "address any
 * group of people in the organisation".
 *
 * <h2>Why the refusals are asserted to be indistinguishable</h2>
 *
 * <p>Several tests below assert that two different failures produce the same exception carrying no
 * detail. That looks like under-testing and is the opposite: a resolver that said "no such role" for
 * one and "role not targetable" for the other would let an announcer enumerate the organisation's
 * role structure by publishing to guesses and reading the errors. The absence of the detail is the
 * feature, so its absence is what the tests pin.
 *
 * <p>The operator-facing detail is logged, which is where it belongs - the person who needs to know
 * which half failed is the one who can read the configuration.
 */
class AudienceResolverTest {

    private static final String ALLOWED_ROLE = "ADMIN";

    @Test
    @DisplayName("a permitted EVERYONE audience resolves and carries no value")
    void everyoneResolves() {
        Audience audience = resolver(permitting("EVERYONE", "ROLE")).resolve(
                AudienceType.EVERYONE, null);

        assertThat(audience.type()).isEqualTo(AudienceType.EVERYONE);
        assertThat(audience.value())
                .as("a value here would be ambiguous: everybody, or only that value?")
                .isNull();
    }

    @Test
    @DisplayName("a role on the allowlist resolves")
    void allowedRoleResolves() {
        Audience audience = resolver(permitting("EVERYONE", "ROLE")).resolve(
                AudienceType.ROLE, ALLOWED_ROLE);

        assertThat(audience.type()).isEqualTo(AudienceType.ROLE);
        assertThat(audience.value()).isEqualTo(ALLOWED_ROLE);
    }

    @Test
    @DisplayName("an audience kind the deployment has not permitted is refused")
    void unpermittedKindIsRefused() {
        assertThatThrownBy(() -> resolver(permitting("ROLE")).resolve(AudienceType.EVERYONE, null))
                .isInstanceOf(AudienceNotPermittedException.class);
    }

    @Test
    @DisplayName("a role that is not on the allowlist is refused")
    void unlistedRoleIsRefused() {
        assertThatThrownBy(() -> resolver(permitting("EVERYONE", "ROLE"))
                .resolve(AudienceType.ROLE, "SUPPORT"))
                .isInstanceOf(AudienceNotPermittedException.class);
    }

    /**
     * The assertion the enumeration concern actually rests on: a role that does not exist anywhere
     * and a role that exists but is not targetable must be refused identically, including the message
     * and the absence of any machine-readable property naming either.
     */
    @Test
    @DisplayName("an unlisted role and a nonexistent role are refused identically")
    void refusalsAreIndistinguishable() {
        AnnouncementAudienceResolver resolver = resolver(permitting("EVERYONE", "ROLE"));

        AudienceNotPermittedException unlisted = catchRefusal(resolver, "SUPPORT");
        AudienceNotPermittedException nonexistent = catchRefusal(resolver, "ROLE_THAT_NEVER_EXISTED");

        assertThat(nonexistent.getMessage()).isEqualTo(unlisted.getMessage());
        assertThat(nonexistent.getClass()).isEqualTo(unlisted.getClass());
    }

    /**
     * A refused kind and a refused role are also the same exception. An announcer must not be able to
     * learn which of the two policy lists rejected them, because that is a second bit of information
     * about the deployment's configuration.
     */
    @Test
    @DisplayName("a refused kind and a refused role are the same failure")
    void kindAndRoleRefusalsAreTheSame() {
        AudienceNotPermittedException kindRefused = catchThrowable(
                () -> resolver(permitting("ROLE")).resolve(AudienceType.EVERYONE, null));
        AudienceNotPermittedException roleRefused = catchRefusal(
                resolver(permitting("EVERYONE", "ROLE")), "SUPPORT");

        assertThat(kindRefused.getMessage()).isEqualTo(roleRefused.getMessage());
    }

    /**
     * Case-sensitive on purpose. The projection stores role codes exactly as the directory names
     * them, and a case-insensitive match would permit targeting {@code admin} when the reviewed list
     * said {@code ADMIN} - a difference nobody would notice in a configuration diff.
     */
    @Test
    @DisplayName("the allowlist match is case-sensitive")
    void matchIsCaseSensitive() {
        assertThatThrownBy(() -> resolver(permitting("EVERYONE", "ROLE"))
                .resolve(AudienceType.ROLE, "admin"))
                .isInstanceOf(AudienceNotPermittedException.class);
    }

    @Test
    @DisplayName("a role audience with no role code is refused")
    void roleWithNoCodeIsRefused() {
        assertThatThrownBy(() -> resolver(permitting("EVERYONE", "ROLE"))
                .resolve(AudienceType.ROLE, null))
                .isInstanceOf(AudienceNotPermittedException.class);
    }

    /**
     * An empty allowlist permits nothing, rather than permitting everything. The opposite default is
     * the classic way a policy list becomes decorative.
     */
    @Test
    @DisplayName("an empty allowlist permits no role at all")
    void emptyAllowlistPermitsNothing() {
        NotificationProperties properties = permitting("EVERYONE", "ROLE");
        properties.getAnnouncements().setTargetableRoles(List.of());

        assertThatThrownBy(() -> resolver(properties).resolve(AudienceType.ROLE, ALLOWED_ROLE))
                .isInstanceOf(AudienceNotPermittedException.class);
    }

    private static AudienceNotPermittedException catchRefusal(AnnouncementAudienceResolver resolver,
                                                              String role) {
        return catchThrowable(() -> resolver.resolve(AudienceType.ROLE, role));
    }

    private static AudienceNotPermittedException catchThrowable(Runnable call) {
        try {
            call.run();
        } catch (AudienceNotPermittedException e) {
            return e;
        }
        throw new AssertionError("expected the audience to be refused");
    }

    private static AnnouncementAudienceResolver resolver(NotificationProperties properties) {
        return new AnnouncementAudienceResolver(properties);
    }

    private static NotificationProperties permitting(String... kinds) {
        NotificationProperties properties = new NotificationProperties();
        properties.getAnnouncements().setAllowedAudiences(List.of(kinds));
        properties.getAnnouncements().setTargetableRoles(List.of(ALLOWED_ROLE));
        return properties;
    }
}
