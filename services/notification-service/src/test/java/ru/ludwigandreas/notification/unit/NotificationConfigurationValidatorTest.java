package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import ru.ludwigandreas.notification.config.NotificationConfigurationValidator;
import ru.ludwigandreas.notification.service.preference.ConfiguredPreferenceSource;
import ru.ludwigandreas.job.core.config.JobCoreProperties;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * Every check here describes a relationship between two settings that are individually valid and
 * jointly wrong - the class of mistake Bean Validation cannot see, and the class whose symptoms are
 * intermittent and nearly impossible to attribute weeks later.
 */
class NotificationConfigurationValidatorTest {

    @Test
    @DisplayName("the shipped defaults start")
    void defaultsAreSafe() {
        assertThatCode(() -> validator(defaults()).validate()).doesNotThrowAnyException();
    }

    /**
     * The single worst bug this service can have: the sweeper reclaims deliveries a healthy pod is
     * still sending, and a second pod sends them again. It only appears under load, and the symptom -
     * "some customers got two emails" - points at nothing.
     */
    @Test
    @DisplayName("a lease shorter than the worst-case cycle is refused, naming the double-send")
    void refusesALeaseThatCannotOutlastACycle() {
        NotificationProperties properties = defaults();
        properties.getQueue().setBatchSize(50);
        properties.getQueue().setLeaseTimeout(Duration.ofSeconds(30));
        properties.getChannels().getChat().setEnabled(true);
        properties.getChannels().getChat().setBaseUrl("https://chat.internal");
        properties.getChannels().getChat().setReadTimeout(Duration.ofSeconds(10));

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease-timeout")
                .hasMessageContaining("twice");
    }

    @Test
    @DisplayName("a lease no longer than the poll interval is refused")
    void refusesALeaseShorterThanThePollInterval() {
        NotificationProperties properties = defaults();
        properties.getQueue().setBatchSize(1);
        properties.getQueue().setPollInterval(Duration.ofMinutes(10));
        properties.getQueue().setLeaseTimeout(Duration.ofMinutes(5));

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("poll-interval");
    }

    /**
     * A reserve equal to the batch size means the general pass never claims anything, so normal and
     * bulk traffic stops entirely - and looks, from outside, exactly like a stuck queue.
     */
    @Test
    @DisplayName("a high-priority reserve that consumes the whole batch is refused")
    void refusesAReserveThatStarvesEverythingElse() {
        NotificationProperties properties = defaults();
        properties.getQueue().setBatchSize(20);
        properties.getQueue().setHighPriorityReserve(20);

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("high-priority-reserve");
    }

    /**
     * The lease is the failover time: a pod that dies holding the lock blocks that job until the
     * lease lapses. Longer than the schedule and one killed pod costs several consecutive runs, while
     * the scheduler still looks perfectly healthy.
     */
    @Test
    @DisplayName("a lock lease longer than the schedule it guards is refused")
    void refusesALeaseLongerThanTheSchedule() {
        NotificationProperties properties = defaults();
        properties.getRetention().setRunInterval(Duration.ofMinutes(10));
        JobCoreProperties jobCore = jobCoreDefaults();
        jobCore.getLock().setDefaultLease(Duration.ofMinutes(30));

        assertThatThrownBy(() -> validator(properties, jobCore).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-lease");
    }

    /**
     * A job that is switched off has no schedule to outlast, and failing a deployment over one would
     * be a startup error about nothing.
     */
    @Test
    @DisplayName("the lease is not checked against a job this deployment has disabled")
    void ignoresTheScheduleOfADisabledJob() {
        NotificationProperties properties = defaults();
        properties.getRetention().setEnabled(false);
        JobCoreProperties jobCore = jobCoreDefaults();
        jobCore.getLock().setDefaultLease(Duration.ofHours(12));

        validator(properties, jobCore).validate();
    }

    /**
     * An empty signing key would produce signatures every receiver accepts from anyone, which is
     * worse than the channel simply failing.
     */
    @Test
    @DisplayName("an enabled webhook channel with no signing secret is refused")
    void refusesAWebhookWithoutASecret() {
        NotificationProperties properties = defaults();
        properties.getChannels().getWebhook().setEnabled(true);
        properties.getChannels().getWebhook().setSigningSecret("");

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signing-secret");
    }

    @Test
    @DisplayName("an enabled receipt endpoint with no secret is refused, naming the forged bounce")
    void refusesReceiptsWithoutASecret() {
        NotificationProperties properties = defaults();
        properties.getReceipts().setEnabled(true);
        properties.getReceipts().setSigningSecret(null);

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forged bounce");
    }

    @Test
    @DisplayName("an enabled chat channel with no base URL is refused")
    void refusesChatWithoutABaseUrl() {
        NotificationProperties properties = defaults();
        properties.getChannels().getChat().setEnabled(true);

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }

    /**
     * A zero window makes every notification its own window, so nothing ever collapses and every
     * batched delivery waits for a run that finds exactly one member - strictly worse than not
     * digesting at all.
     */
    @Test
    @DisplayName("a non-positive digest window is refused")
    void refusesANonPositiveDigestWindow() {
        NotificationProperties properties = defaults();
        properties.getDigest().setEnabled(true);
        properties.getDigest().getCategories().put("activity", Duration.ZERO);

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("activity");
    }

    @Test
    @DisplayName("retention windows that do not widen outwards are refused")
    void refusesInvertedRetention() {
        NotificationProperties properties = defaults();
        properties.getRetention().setContentTtl(Duration.ofDays(200));

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("content-ttl");
    }

    @Test
    @DisplayName("a delivery that would outlive its own audit trail is refused")
    void refusesHistoryShorterThanDeliveries() {
        NotificationProperties properties = defaults();
        properties.getRetention().setHistoryTtl(Duration.ofDays(1));

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("history-ttl");
    }

    @Test
    @DisplayName("an inbox body that would outlive its own item is refused")
    void refusesInboxContentOutlivingTheItem() {
        NotificationProperties properties = defaults();
        properties.getRetention().setInboxContentTtl(Duration.ofDays(365));

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inbox-content-ttl");
    }

    /**
     * The inverse mistake, and the likelier one. The inbox's windows are months where the delivery
     * body's is days, and the first instinct on reading that is that one of them is a typo -
     * "correcting" the inbox down to 7d would make the inbox expire before the push attempt it
     * exists to outlive.
     */
    @Test
    @DisplayName("an inbox window shorter than the delivery body's is refused")
    void refusesInboxShorterThanDeliveryContent() {
        NotificationProperties properties = defaults();
        properties.getRetention().setInboxTtl(Duration.ofDays(1));
        properties.getRetention().setInboxContentTtl(Duration.ofDays(1));

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inbox-ttl");
    }

    /**
     * The ceiling must ship unset. A default here would discard unread notifications on the upgrade
     * that introduced it, which is the kind of data loss nobody attributes to a retention default.
     */
    @Test
    @DisplayName("the unread ceiling is unset by default and a valid configuration starts")
    void unreadCeilingIsUnsetByDefault() {
        NotificationProperties properties = defaults();

        assertThat(properties.getRetention().getInboxUnreadMaxAge()).isNull();
        validator(properties).validate();
    }

    // ---------------------------------------------------------------------------------------------
    // The announcement catalogue, checked at startup rather than at publish
    // ---------------------------------------------------------------------------------------------

    /**
     * The control for the whole group below: a deployment that has not switched announcements on
     * must not be failed over a catalogue it does not use.
     */
    @Test
    @DisplayName("an empty announcement catalogue is fine while announcements are off")
    void disabledAnnouncementsAreNotValidated() {
        NotificationProperties properties = defaults();

        assertThat(properties.getAnnouncements().isEnabled()).isFalse();
        validator(properties).validate();
    }

    @Test
    @DisplayName("announcements enabled with no permitted audience is refused")
    void refusesEnabledWithNoAudience() {
        NotificationProperties properties = announcementsEnabled();
        properties.getAnnouncements().getAllowedAudiences().clear();

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-audiences");
    }

    @Test
    @DisplayName("announcements enabled with an empty catalogue is refused")
    void refusesEnabledWithNoCategories() {
        NotificationProperties properties = announcementsEnabled();
        properties.getAnnouncements().getCategories().clear();

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("categories");
    }

    @Test
    @DisplayName("an unknown audience kind is refused, naming the known ones")
    void refusesUnknownAudienceKind() {
        NotificationProperties properties = announcementsEnabled();
        properties.getAnnouncements().setAllowedAudiences(List.of("ORG_UNIT"));

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ORG_UNIT")
                .hasMessageContaining("EVERYONE");
    }

    /**
     * The mistake with the quietest consequence. A category class that does not exist fails here; a
     * category class that exists but is the wrong one cannot be detected by any build, which is why
     * that is recorded as an unmechanisable rule rather than pretended about.
     */
    @Test
    @DisplayName("an unknown category class is refused, naming the category")
    void refusesUnknownCategoryClass() {
        NotificationProperties properties = announcementsEnabled();
        properties.getAnnouncements().getCategories().get("platform-release")
                .setCategoryClass("URGENT");

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform-release")
                .hasMessageContaining("URGENT");
    }

    @Test
    @DisplayName("an unknown channel is refused, naming the category")
    void refusesUnknownChannel() {
        NotificationProperties properties = announcementsEnabled();
        properties.getAnnouncements().getCategories().get("platform-release")
                .setChannels(List.of("IN_APP", "SMS"));

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform-release")
                .hasMessageContaining("SMS");
    }

    /**
     * An announcement purged while it is still visible presents as a banner vanishing mid-incident,
     * which is attributable to nothing. Hence a startup check rather than a comment.
     */
    @Test
    @DisplayName("a retention window shorter than the longest visibility window is refused")
    void refusesRetentionShorterThanVisibility() {
        NotificationProperties properties = announcementsEnabled();
        properties.getAnnouncements().setMaxVisibilityWindow(Duration.ofDays(180));

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("announcement-ttl");
    }

    @Test
    @DisplayName("a valid catalogue starts")
    void validCatalogueStarts() {
        validator(announcementsEnabled()).validate();
    }

    /** Enabled, with one inbox-only and one emailing category - the shape a real deployment has. */
    private static NotificationProperties announcementsEnabled() {
        NotificationProperties properties = defaults();
        NotificationProperties.Announcements announcements = properties.getAnnouncements();
        announcements.setEnabled(true);
        announcements.setAllowedAudiences(new java.util.ArrayList<>(List.of("EVERYONE", "ROLE")));
        announcements.setTargetableRoles(new java.util.ArrayList<>(List.of("ADMIN")));

        NotificationProperties.Announcements.AnnouncementCategory release =
                new NotificationProperties.Announcements.AnnouncementCategory();
        release.setCategoryClass("PLATFORM");
        release.setChannels(new java.util.ArrayList<>(List.of("IN_APP")));
        announcements.getCategories().put("platform-release", release);

        NotificationProperties.Announcements.AnnouncementCategory incident =
                new NotificationProperties.Announcements.AnnouncementCategory();
        incident.setCategoryClass("PLATFORM");
        incident.setChannels(new java.util.ArrayList<>(List.of("IN_APP", "EMAIL")));
        announcements.getCategories().put("platform-incident", incident);
        return properties;
    }

    /**
     * Reporting one problem at a time turns a bad rollout into several rounds of deploy-and-read;
     * reporting all of them turns it into one edit.
     */
    @Test
    @DisplayName("every problem is reported at once, not only the first")
    void reportsEveryProblem() {
        NotificationProperties properties = defaults();
        properties.getQueue().setHighPriorityReserve(properties.getQueue().getBatchSize());
        JobCoreProperties jobCore = jobCoreDefaults();
        jobCore.getLock().setDefaultLease(Duration.ofDays(1));

        assertThatThrownBy(() -> validator(properties, jobCore).validate())
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(failure.getMessage())
                        .contains("high-priority-reserve")
                        .contains("default-lease"));
    }

    /**
     * JavaMail's own default is "wait forever", which no lease can outlast - so an unset timeout must
     * not be read as zero, or the lease check would pass on exactly the configuration that needs it.
     */
    @Test
    @DisplayName("an unset SMTP timeout is not treated as instantaneous")
    void unsetSmtpTimeoutIsNotZero() {
        NotificationProperties properties = defaults();
        properties.getChannels().getEmail().setEnabled(true);
        properties.getQueue().setBatchSize(50);
        properties.getQueue().setLeaseTimeout(Duration.ofMinutes(5));

        assertThatThrownBy(() -> new NotificationConfigurationValidator(properties, jobCoreDefaults(),
                new MockEnvironment(), new ConfiguredPreferenceSource()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease-timeout");
    }

    private static NotificationConfigurationValidator validator(NotificationProperties properties) {
        return validator(properties, jobCoreDefaults());
    }

    private static NotificationConfigurationValidator validator(NotificationProperties properties,
                                                                JobCoreProperties jobCore) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.mail.properties.mail.smtp.timeout", "10000");
        return new NotificationConfigurationValidator(
                properties, jobCore, environment, new ConfiguredPreferenceSource());
    }

    /**
     * The module default, five minutes - not the two minutes this service's application.yml sets, so
     * that a case which cares about the lease says so rather than inheriting a number from a file
     * these tests do not read.
     */
    private static JobCoreProperties jobCoreDefaults() {
        return new JobCoreProperties();
    }

    /** The shipped defaults, with the two secrets a deployment must supply. */
    private static NotificationProperties defaults() {
        NotificationProperties properties = new NotificationProperties();
        properties.getReceipts().setSigningSecret("receipt-secret");
        return properties;
    }
}
