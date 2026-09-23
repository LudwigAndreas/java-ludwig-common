package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import ru.ludwigandreas.notification.config.NotificationConfigurationValidator;
import ru.ludwigandreas.notification.service.preference.ConfiguredPreferenceSource;
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

    @Test
    @DisplayName("a heartbeat that cannot fire several times per lease is refused")
    void refusesASlowHeartbeat() {
        NotificationProperties properties = defaults();
        properties.getLocks().setLease(Duration.ofMinutes(2));
        properties.getLocks().setHeartbeat(Duration.ofMinutes(1));

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("locks.heartbeat");
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

    /**
     * Reporting one problem at a time turns a bad rollout into several rounds of deploy-and-read;
     * reporting all of them turns it into one edit.
     */
    @Test
    @DisplayName("every problem is reported at once, not only the first")
    void reportsEveryProblem() {
        NotificationProperties properties = defaults();
        properties.getQueue().setHighPriorityReserve(properties.getQueue().getBatchSize());
        properties.getLocks().setHeartbeat(properties.getLocks().getLease());

        assertThatThrownBy(() -> validator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(failure.getMessage())
                        .contains("high-priority-reserve")
                        .contains("locks.heartbeat"));
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

        assertThatThrownBy(() -> new NotificationConfigurationValidator(properties,
                new MockEnvironment(), new ConfiguredPreferenceSource()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease-timeout");
    }

    private static NotificationConfigurationValidator validator(NotificationProperties properties) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.mail.properties.mail.smtp.timeout", "10000");
        return new NotificationConfigurationValidator(
                properties, environment, new ConfiguredPreferenceSource());
    }

    /** The shipped defaults, with the two secrets a deployment must supply. */
    private static NotificationProperties defaults() {
        NotificationProperties properties = new NotificationProperties();
        properties.getReceipts().setSigningSecret("receipt-secret");
        return properties;
    }
}
