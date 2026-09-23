package ru.ludwigandreas.notification.integration;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestComponent;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.entity.UserStatus;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.preference.usersettings.NotificationSettings;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.event.UserSettingChangedEvent;
import ru.ludwigandreas.usersettings.projection.SettingsProjectionService;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;

/**
 * Sets up a recipient the way the estate actually does, now that this service stores neither half.
 *
 * <p>A contact record is a row in the identity projection, as the OIDC stream would have written it.
 * A preference is a projected setting event, applied through the same service the Kafka listener
 * calls - not a row inserted behind its back, because the ordering and idempotence rules that make
 * the replica correct live in that service and a test that bypassed them would be testing a
 * different program.
 */
@TestComponent
@RequiredArgsConstructor
public class RecipientFixtures {

    /**
     * The tenant every test recipient belongs to, and therefore the one their settings are under.
     *
     * <p>The same tenant the test callers are in. It has to be: a delivery inherits its tenant from
     * the recipient's directory record, and the data-scope tests assert that support in that tenant
     * sees it. Giving fixtures a tenant of their own would make those assertions pass or fail on the
     * fixture rather than on the policy.
     */
    public static final String TENANT = TestPrincipals.TENANT;

    private final SecurityUserRepository users;
    private final SettingsProjectionService projection;
    private final UserSettingValueRepository settings;

    /** Forgets every recipient and every projected preference. */
    public void reset() {
        settings.deleteAll();
        users.deleteAll();
    }

    /** A user the OIDC provider has told us about, with a verified address. */
    public void givenUser(String userId, String email) {
        SecurityUserEntity user = new SecurityUserEntity();
        user.setId(userId);
        user.setDisplayName(userId);
        user.setTenantId(TENANT);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmail(email);
        user.setEmailVerified(true);
        user.setSourceSystem("oidc");
        user.setSourceTimestamp(Instant.now());
        users.saveAndFlush(user);
    }

    /** The same, with an address the provider has explicitly not verified. */
    public void givenUserWithUnverifiedEmail(String userId, String email) {
        givenUser(userId, email);
        SecurityUserEntity user = users.findById(userId).orElseThrow();
        user.setEmailVerified(false);
        users.saveAndFlush(user);
    }

    /**
     * Projects an opt-out for one category on one channel, as the account service would publish it.
     *
     * @param category {@link NotificationSettings#ALL_CATEGORIES} for the blanket opt-out
     */
    public void givenOptOut(String userId, String category, ChannelType channel) {
        givenSetting(userId, NotificationSettings.optOut(category, channel).getKey(), "true", "boolean");
    }

    /** Projects an explicit opt-in, which beats a blanket opt-out for that one category. */
    public void givenOptIn(String userId, String category, ChannelType channel) {
        givenSetting(userId, NotificationSettings.optOut(category, channel).getKey(), "false", "boolean");
    }

    /** Projects a quiet-hours window, as the account service would publish it. */
    public void givenQuietHours(String userId, String startTime, String endTime) {
        givenSetting(userId, "user.notifications.quiet-hours",
                "{\"enabled\":true,\"start\":\"" + startTime + "\",\"end\":\"" + endTime + "\"}", "json");
    }

    /** Projects the recipient's locale. */
    public void givenLocale(String userId, String languageTag) {
        givenSetting(userId, "user.locale", languageTag, "locale");
    }

    /** Projects the recipient's timezone. */
    public void givenTimezone(String userId, String zoneId) {
        givenSetting(userId, "user.timezone", zoneId, "zone-id");
    }

    private void givenSetting(String userId, String key, String value, String valueType) {
        projection.apply(new UserSettingChangedEvent(
                UUID.randomUUID().toString(),
                TENANT,
                SettingLayer.USER,
                userId,
                key,
                value,
                valueType,
                false,
                Instant.now()));
    }
}
