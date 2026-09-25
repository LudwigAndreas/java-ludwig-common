package ru.ludwigandreas.notification.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.ConfigurableEnvironment;
import ru.ludwigandreas.job.core.config.JobCoreProperties;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.preference.ConfiguredPreferenceSource;
import ru.ludwigandreas.notification.service.preference.RecipientPreferenceSource;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * Refuses to start on a configuration that is individually valid and jointly wrong.
 *
 * <p>Bean Validation checks one field at a time, which catches a negative batch size and misses every
 * interesting mistake in this service. The failures below are all relationships between two settings,
 * and each of them produces behaviour that is intermittent, rare, and nearly impossible to attribute
 * weeks after the change that caused it - a delivery sent twice during a busy hour, a lock that
 * occasionally admits two holders, a channel that signs with an empty key. Failing the pod at startup
 * is the cheapest possible way to learn about them.
 *
 * <p>Every check states what breaks rather than what is wrong, because the person reading the message
 * is deploying at the time and needs to know whether to roll back.
 */
@Slf4j
public class NotificationConfigurationValidator {

    /**
     * How much longer the lease must be than the worst-case cycle.
     *
     * <p>A cycle can take up to {@code batchSize} provider calls at the channel's read timeout, and
     * the lease must outlast that or the sweeper reclaims rows a healthy pod is still sending.
     * Doubling leaves room for a garbage-collection pause and for a provider that is slow rather than
     * dead.
     */
    private static final int LEASE_SAFETY_FACTOR = 2;

    private final NotificationProperties properties;
    private final JobCoreProperties jobCoreProperties;
    private final ConfigurableEnvironment environment;
    private final RecipientPreferenceSource preferenceSource;

    public NotificationConfigurationValidator(NotificationProperties properties,
                                              JobCoreProperties jobCoreProperties,
                                              ConfigurableEnvironment environment,
                                              RecipientPreferenceSource preferenceSource) {
        this.properties = properties;
        this.jobCoreProperties = jobCoreProperties;
        this.environment = environment;
        this.preferenceSource = preferenceSource;
    }

    /** @throws IllegalStateException listing every problem found, rather than only the first */
    @PostConstruct
    public void validate() {
        List<String> problems = new ArrayList<>();
        checkLease(problems);
        checkLockLease(problems);
        checkHighPriorityReserve(problems);
        checkWebhookSecret(problems);
        checkReceiptSecret(problems);
        checkChatBaseUrl(problems);
        checkDigestCategories(problems);
        checkRetentionOrdering(problems);
        checkPreferenceSource(problems);

        if (!problems.isEmpty()) {
            throw new IllegalStateException("ludwig.notification configuration is unsafe:\n  - "
                    + String.join("\n  - ", problems));
        }
        log.info("Notification configuration validated: batch {}, lease {}, poll every {}; "
                        + "recipient preferences from {}",
                properties.getQueue().getBatchSize(), properties.getQueue().getLeaseTimeout(),
                properties.getQueue().getPollInterval(), preferenceSource.describe());
    }

    /**
     * The lease must outlast the slowest legitimate cycle.
     *
     * <p>If it does not, the stale sweeper reclaims deliveries a healthy pod is still dispatching, and
     * a second pod sends them again. That is the single worst bug this service can have, it only
     * appears under load, and the symptom - "some customers got two emails" - points at nothing.
     */
    private void checkLease(List<String> problems) {
        NotificationProperties.Queue queue = properties.getQueue();
        Duration slowestCall = slowestChannelTimeout();
        Duration worstCase = slowestCall.multipliedBy(queue.getBatchSize());
        Duration required = worstCase.multipliedBy(LEASE_SAFETY_FACTOR);

        if (queue.getLeaseTimeout().compareTo(required) < 0) {
            problems.add(String.format(
                    "queue.lease-timeout (%s) is shorter than %d x the worst-case cycle (%d deliveries "
                            + "x %s = %s). The stale sweeper would reclaim deliveries a healthy pod is "
                            + "still sending, and they would go out twice. Raise the lease, lower "
                            + "queue.batch-size, or lower the channel read timeouts.",
                    queue.getLeaseTimeout(), LEASE_SAFETY_FACTOR, queue.getBatchSize(), slowestCall,
                    worstCase));
        }
        if (queue.getLeaseTimeout().compareTo(queue.getPollInterval()) <= 0) {
            problems.add(String.format(
                    "queue.lease-timeout (%s) is not longer than queue.poll-interval (%s). The next "
                            + "cycle would reclaim the previous one's leases before it finished.",
                    queue.getLeaseTimeout(), queue.getPollInterval()));
        }
    }

    /**
     * A lock lease must not outlast the interval between the runs it guards.
     *
     * <p>This check replaced a heartbeat-vs-lease one when the lock moved to {@code job-core}. That
     * check had stopped describing anything real: neither scheduler renews on a timer, they renew
     * between work items, so there was no heartbeat interval to be too long. The relationship that
     * <em>is</em> real is this one, and it is the reason the lease javadoc has always said "short
     * enough that a missed window is one window".
     *
     * <p>The lease is the failover time - a pod that dies holding the lock blocks that job for
     * exactly this long, because nothing releases it and nothing can take it until it lapses. Make it
     * longer than the schedule and one killed pod costs several consecutive runs, a digest backlog
     * builds up behind a job that looks perfectly healthy in the scheduler, and the first symptom is
     * a batch of digests that all arrive at once hours later.
     *
     * <p>Only jobs that are actually enabled are considered. Failing a deployment over the schedule
     * of a job it has switched off would be a startup error about nothing.
     */
    private void checkLockLease(List<String> problems) {
        Duration lease = jobCoreProperties.getLock().getDefaultLease();
        if (properties.getDigest().isEnabled()) {
            checkLeaseAgainstSchedule(problems, lease, "digest", properties.getDigest().getRunInterval());
        }
        if (properties.getRetention().isEnabled()) {
            checkLeaseAgainstSchedule(problems, lease, "retention",
                    properties.getRetention().getRunInterval());
        }
    }

    private void checkLeaseAgainstSchedule(List<String> problems, Duration lease, String job,
                                           Duration runInterval) {
        if (lease.compareTo(runInterval) > 0) {
            problems.add(String.format(
                    "ludwig.job-core.lock.default-lease (%s) is longer than %s.run-interval (%s). A "
                            + "pod that died holding the lock would block more than one run, and the "
                            + "backlog would build behind a job that still looks scheduled.",
                    lease, job, runInterval));
        }
    }

    /**
     * The reserved high-priority pass must leave room for everything else.
     *
     * <p>A reserve equal to the batch size means the general pass never claims anything, so normal and
     * bulk traffic stops entirely - and looks, from the outside, exactly like a stuck queue.
     */
    private void checkHighPriorityReserve(List<String> problems) {
        NotificationProperties.Queue queue = properties.getQueue();
        if (queue.getHighPriorityReserve() >= queue.getBatchSize()) {
            problems.add(String.format(
                    "queue.high-priority-reserve (%d) is not smaller than queue.batch-size (%d). The "
                            + "general pass would never claim anything and NORMAL and BULK traffic "
                            + "would stop.",
                    queue.getHighPriorityReserve(), queue.getBatchSize()));
        }
    }

    /**
     * An enabled webhook channel must have a signing secret.
     *
     * <p>Without one the channel cannot sign at all, and the failure would surface as every webhook
     * delivery dead-lettering - which is loud but misleading. Worse, an implementation that defaulted
     * to an empty key would produce signatures any receiver would accept from anyone.
     */
    private void checkWebhookSecret(List<String> problems) {
        NotificationProperties.Webhook webhook = properties.getChannels().getWebhook();
        if (webhook.isEnabled() && isBlank(webhook.getSigningSecret())) {
            problems.add("channels.webhook is enabled but channels.webhook.signing-secret is empty. "
                    + "Every webhook delivery would fail, and an empty key would be worse. Supply it "
                    + "from Vault, or disable the channel.");
        }
    }

    /** An enabled receipt endpoint must have a verification secret, for the same reason. */
    private void checkReceiptSecret(List<String> problems) {
        NotificationProperties.Receipts receipts = properties.getReceipts();
        if (receipts.isEnabled() && isBlank(receipts.getSigningSecret())) {
            problems.add("receipts are enabled but receipts.signing-secret is empty. The receipt "
                    + "endpoint is reachable by anybody, and a forged bounce suppresses a real "
                    + "recipient's address.");
        }
    }

    private void checkChatBaseUrl(List<String> problems) {
        NotificationProperties.Chat chat = properties.getChannels().getChat();
        if (chat.isEnabled() && isBlank(chat.getBaseUrl())) {
            problems.add("channels.chat is enabled but channels.chat.base-url is empty.");
        }
    }

    /**
     * A deployment that declared stored preferences load-bearing must actually have them.
     *
     * <p>{@code AUTO} deliberately falls back in silence, because that is the state this service ships
     * in and the state a migration passes through. {@code USER_SETTINGS} is the operator saying the
     * fallback is no longer acceptable here, and the failure it prevents is the quiet one: every
     * per-recipient opt-out reads as "has not opted out", so a campaign goes to people who declined
     * it and nothing in the logs says why. A pod that will not start is a far cheaper way to find out.
     *
     * <p>The test is which implementation won the {@code @Primary} contest rather than whether the
     * module is on the classpath, because the failure mode being guarded against is the module being
     * present and not started - see {@code UserSettingsPreferenceConfig}. It is written against
     * {@link ConfiguredPreferenceSource}, a class this service always has, so the check itself does
     * not reintroduce the optional dependency it exists to police.
     */
    private void checkPreferenceSource(List<String> problems) {
        if (properties.getPreferences().getSource()
                != NotificationProperties.Preferences.Source.USER_SETTINGS) {
            return;
        }
        if (preferenceSource instanceof ConfiguredPreferenceSource) {
            problems.add("preferences.source is USER_SETTINGS but preferences are resolving from "
                    + "configuration, so every per-recipient opt-out and quiet-hours window would read "
                    + "as unset. Add user-settings-spring-boot-starter and enable a mode "
                    + "(ludwig.user-settings.projection.enabled or .owner.enabled), or set "
                    + "preferences.source to AUTO to accept configured defaults.");
        }
    }

    /**
     * A digest window must be positive.
     *
     * <p>A zero or negative window makes every notification its own window, so nothing ever collapses
     * and every batched delivery waits for a digest run that will always find exactly one member -
     * which is strictly worse than not digesting at all.
     */
    private void checkDigestCategories(List<String> problems) {
        if (!properties.getDigest().isEnabled()) {
            return;
        }
        properties.getDigest().getCategories().forEach((category, window) -> {
            if (window == null || window.isZero() || window.isNegative()) {
                problems.add("digest.categories." + category + " has a non-positive window ("
                        + window + "). Nothing would ever collapse.");
            }
        });
    }

    /**
     * The retention windows must widen outwards.
     *
     * <p>Keeping a rendered body longer than the delivery that owns it, or a delivery longer than its
     * audit trail, means the purge deletes the parent and orphans the child - and the promise the
     * retention policy makes in the README stops being true in the direction that matters.
     */
    private void checkRetentionOrdering(List<String> problems) {
        NotificationProperties.Retention retention = properties.getRetention();
        if (retention.getContentTtl().compareTo(retention.getDeliveryTtl()) > 0) {
            problems.add(String.format(
                    "retention.content-ttl (%s) exceeds retention.delivery-ttl (%s): rendered bodies "
                            + "would outlive the deliveries that own them.",
                    retention.getContentTtl(), retention.getDeliveryTtl()));
        }
        if (retention.getRecipientDataTtl().compareTo(retention.getDeliveryTtl()) > 0) {
            problems.add(String.format(
                    "retention.recipient-data-ttl (%s) exceeds retention.delivery-ttl (%s): addresses "
                            + "would never be scrubbed before their rows are deleted, so the scrub "
                            + "would never run.",
                    retention.getRecipientDataTtl(), retention.getDeliveryTtl()));
        }
        if (retention.getHistoryTtl().compareTo(retention.getDeliveryTtl()) < 0) {
            problems.add(String.format(
                    "retention.history-ttl (%s) is shorter than retention.delivery-ttl (%s): a "
                            + "delivery would outlive its own audit trail.",
                    retention.getHistoryTtl(), retention.getDeliveryTtl()));
        }
    }

    /** The longest a single provider call may block, across every enabled channel. */
    private Duration slowestChannelTimeout() {
        NotificationProperties.Channels channels = properties.getChannels();
        Duration slowest = Duration.ZERO;
        for (ChannelType channel : ChannelType.values()) {
            Duration timeout = switch (channel) {
                case CHAT -> channels.getChat().isEnabled()
                        ? channels.getChat().getReadTimeout() : Duration.ZERO;
                case WEBHOOK -> channels.getWebhook().isEnabled()
                        ? channels.getWebhook().getReadTimeout() : Duration.ZERO;
                // SMTP's timeouts belong to the mail sender, which is configured under spring.mail.
                // Read from the environment rather than guessed, because a default that happened to be
                // lower than reality would make this whole check permissive in the wrong direction.
                case EMAIL -> channels.getEmail().isEnabled() ? smtpTimeout() : Duration.ZERO;
            };
            if (timeout.compareTo(slowest) > 0) {
                slowest = timeout;
            }
        }
        return slowest.isZero() ? Duration.ofSeconds(1) : slowest;
    }

    /** SMTP's own timeout, in milliseconds, from {@code spring.mail.properties.mail.smtp.timeout}. */
    private Duration smtpTimeout() {
        String configured = environment == null
                ? null
                : environment.getProperty("spring.mail.properties.mail.smtp.timeout");
        if (configured == null || configured.isBlank()) {
            // JavaMail's own default is "wait forever", which no lease can outlast - so an unset
            // timeout is treated as the largest value this check will tolerate rather than as zero.
            return properties.getQueue().getLeaseTimeout();
        }
        try {
            return Duration.ofMillis(Long.parseLong(configured.trim()));
        } catch (NumberFormatException e) {
            return properties.getQueue().getLeaseTimeout();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
