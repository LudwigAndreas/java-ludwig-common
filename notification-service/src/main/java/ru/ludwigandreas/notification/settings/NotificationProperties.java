package ru.ludwigandreas.notification.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every knob this service has, in one validated tree under {@code ludwig.notification}.
 *
 * <p>There is no magic number anywhere in the code below this class. That is not tidiness - a
 * literal in a dispatch loop is a number that cannot be changed without a release, and the numbers
 * that matter here (batch size, lease timeout, rate limit) are exactly the ones an operator needs to
 * change while something is going wrong.
 *
 * <p>{@code @Validated} makes a bad value fail the pod at startup rather than the request at
 * runtime. A negative batch size or a lease shorter than the poll interval is a configuration
 * mistake that produces bizarre, intermittent behaviour hours later; failing to start is the kindest
 * possible outcome, and it is what the {@code configuration-properties} architecture rule requires
 * of every properties class in this repository.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "ludwig.notification")
public class NotificationProperties {

    /** Owner name recorded in delivery leases and distributed locks; defaults to hostname + suffix. */
    private String instanceId;

    @Valid
    private final Ingress ingress = new Ingress();

    @Valid
    private final Idempotency idempotency = new Idempotency();

    @Valid
    private final Templates templates = new Templates();

    @Valid
    private final Queue queue = new Queue();

    @Valid
    private final Retry retry = new Retry();

    @Valid
    private final Channels channels = new Channels();

    @Valid
    private final Preferences preferences = new Preferences();

    @Valid
    private final Recipients recipients = new Recipients();

    @Valid
    private final Digest digest = new Digest();

    @Valid
    private final Retention retention = new Retention();

    @Valid
    private final Locks locks = new Locks();

    @Valid
    private final Receipts receipts = new Receipts();

    @Valid
    private final Events events = new Events();

    /** The Kafka consumer that carries the primary, fire-and-forget ingress. */
    @Getter
    @Setter
    public static class Ingress {

        /**
         * Whether the Kafka listener runs.
         *
         * <p>Off by default, because the REST ingress is the one every deployment has and the broker
         * is the one some do not. A service with no broker that defaulted to consuming would fail to
         * start on a missing container factory, which is a confusing way to learn that a topic was
         * never provisioned; a service with a broker turns this on in one line.
         *
         * <p>Both ingresses may run at once, and on a platform that has Kafka they normally should:
         * REST for anything a human is waiting on, the topic for fire-and-forget fan-out. They
         * converge on the same application service and neither decides anything the other does not -
         * see {@code NotificationService}.
         */
        private boolean kafkaEnabled = false;

        @NotBlank
        private String topic = "platform.notifications.requests";

        /** Every deployment of this service is one consumer group; scaling adds partitions, not groups. */
        @NotBlank
        private String groupId = "notification-service";

        /**
         * Listener threads per instance.
         *
         * <p>Bounded low on purpose: the consumer's only job is to write a request and its deliveries
         * and return. Throughput here is not the bottleneck - the queue is - and a high concurrency
         * only widens the window in which two threads race on the same idempotency key.
         */
        @Positive
        private int concurrency = 2;

        @NotNull
        @Valid
        private Rest rest = new Rest();
    }

    /**
     * The synchronous ingress, and the only one a deployment without a broker has.
     *
     * <p>Peer services call it directly. That makes it the primary ingress on this platform today,
     * which is why it carries a batch endpoint at all: a caller that used to produce a hundred
     * records to a topic should not have to open a hundred connections to replace it.
     */
    @Getter
    @Setter
    public static class Rest {

        /**
         * Requests accepted in one batch call.
         *
         * <p>Bounded, and the bound matters more here than on most collection endpoints. Each item is
         * submitted in its own transaction and fans out into a delivery per recipient per channel, so
         * an unbounded batch is an unbounded unit of work holding an HTTP thread and a connection for
         * as long as it takes. A caller with more than this is asking for a queue, and should page.
         */
        @Positive
        @Max(500)
        private int maxBatchSize = 100;

        /**
         * Whether one failed item fails the whole batch.
         *
         * <p>False, and this is the decision that makes a batch endpoint worth having. A hundred
         * password resets where one names a template that does not exist should produce
         * ninety-nine sent notifications and one reported failure, not zero of either. A caller that
         * genuinely needs all-or-nothing does not want a batch endpoint - it wants one request whose
         * recipients are the batch, which this API already supports and which is atomic by
         * construction.
         */
        private boolean failFast = false;
    }

    /** The consumer-side dedup window - one of the two capabilities the platform does not ship. */
    @Getter
    @Setter
    public static class Idempotency {

        private boolean enabled = true;

        /**
         * How long a key stays claimed.
         *
         * <p>Must comfortably exceed the longest redelivery a broker or a caller will perform. Too
         * short and an at-least-once redelivery arriving after the window sends a second time; too
         * long and the table grows and a caller cannot legitimately reuse a key. A day covers a
         * consumer-group rebalance, an offset reset and a client's own retry budget.
         */
        @NotNull
        private Duration ttl = Duration.ofDays(1);

        @NotBlank
        private String kafkaScope = "kafka";

        @NotBlank
        private String restScope = "rest";
    }

    /** Where templates live and how they are addressed. */
    @Getter
    @Setter
    public static class Templates {

        /**
         * Directory FreeMarker loads from, watched by the hot-reload module.
         *
         * <p>Absent means "load from the classpath", which is what tests and a local run use. A
         * deployment mounts a ConfigMap or a volume here so a wording change is a config rollout and
         * not a release.
         */
        private String directory;

        /** Classpath prefix used when {@link #directory} is unset. */
        @NotBlank
        private String classpathPrefix = "templates";

        /**
         * Locale rendered when the recipient's own has no variant of the template.
         *
         * <p>Falling back to a fixed language rather than to the JVM default, which would make the
         * output depend on the container's environment - so the same notification would read
         * differently depending on which node it happened to be rendered on.
         */
        @NotBlank
        private String defaultLocale = "en";

        /** Whether each render's source hash is recorded, which is what makes a body traceable. */
        private boolean recordRevisions = true;

        /** Whether the rendered body is stored for later inspection. See the retention policy. */
        private boolean storeRenderedBody = true;
    }

    /** The delivery queue: how it is polled, leased and reclaimed. */
    @Getter
    @Setter
    public static class Queue {

        private boolean pollerEnabled = true;

        /** Gives the context time to finish starting before the first claim. */
        @NotNull
        private Duration initialDelay = Duration.ofSeconds(5);

        @NotNull
        private Duration pollInterval = Duration.ofSeconds(2);

        /**
         * Deliveries claimed per channel per cycle.
         *
         * <p>Bigger batches amortize the claim statement but lengthen the window in which a crashed
         * pod holds leases, and every row in a batch is dispatched serially - so the batch size
         * multiplied by the slowest provider timeout is the worst-case cycle, and the lease has to
         * outlast it. That relationship is checked at startup, and it is the reason this number is
         * 25 rather than something rounder: at 50 and a ten-second SMTP timeout the lease would have
         * to be seventeen minutes, and a seventeen-minute lease means a SIGKILLed pod strands its
         * deliveries for seventeen minutes.
         */
        @Positive
        private int batchSize = 25;

        /**
         * Share of each batch claimed in a pass restricted to {@code HIGH} priority.
         *
         * <p>This is what makes the lanes real rather than decorative. Ordering by priority alone
         * would still let a hundred-thousand-row bulk campaign fill every batch with rows that are
         * merely *older*; reserving part of the budget for a pass that only HIGH rows can satisfy
         * guarantees a password reset a slot no matter how deep the bulk backlog is.
         */
        @Min(0)
        private int highPriorityReserve = 10;

        /**
         * How long a lease may go unrenewed before the sweeper takes it back.
         *
         * <p>Must be longer than the slowest legitimate dispatch of a whole batch, or the sweeper
         * reclaims rows a healthy pod is still working on and they are sent twice. Checked at startup
         * by {@code NotificationConfigurationValidator}.
         *
         * <p>It is also the time a {@code SIGKILL}ed pod's deliveries sit stranded, so it wants to be
         * as short as that first constraint allows rather than comfortably large. Ten minutes covers
         * a 25-delivery batch at a ten-second timeout with margin.
         */
        @NotNull
        private Duration leaseTimeout = Duration.ofMinutes(10);

        @NotNull
        private Duration reclaimInterval = Duration.ofMinutes(1);

        /** How often the queue-depth and oldest-age gauges are recomputed. */
        @NotNull
        private Duration metricsInterval = Duration.ofSeconds(15);

        /**
         * Oldest claimable age past which readiness reports the service as not ready.
         *
         * <p>Readiness, not liveness: a backed-up queue is not fixed by a restart, and wiring it to
         * liveness would turn a provider outage into a restart loop.
         */
        @NotNull
        private Duration readinessMaxPendingAge = Duration.ofMinutes(15);
    }

    /** Backoff for a retryable failure. Applied per delivery, never per request. */
    @Getter
    @Setter
    public static class Retry {

        @Positive
        private int maxAttempts = 8;

        @NotNull
        private Duration initialInterval = Duration.ofSeconds(30);

        @Positive
        private double multiplier = 2.0;

        @NotNull
        private Duration maxInterval = Duration.ofHours(2);

        /**
         * Randomisation applied to each computed delay, as a fraction of it.
         *
         * <p>Without jitter, a provider outage that fails ten thousand deliveries at once schedules
         * all ten thousand retries for the same instant, and the recovering provider is hit by the
         * entire backlog synchronously - which fails them all again, in lockstep, forever. Spreading
         * each delay over a band is what breaks the convoy.
         */
        @Min(0)
        private double jitter = 0.25;
    }

    /** Per-channel transport settings and the cluster-wide throughput limit each one honours. */
    @Getter
    @Setter
    public static class Channels {

        @Valid
        private final Email email = new Email();

        @Valid
        private final Chat chat = new Chat();

        @Valid
        private final Webhook webhook = new Webhook();

        /**
         * Length of a rate-limit window.
         *
         * <p>A fixed window rather than a sliding one because it is one row and one statement, and
         * because the failure it guards against - three replicas each believing they own the whole
         * budget - is not made better by a more precise algorithm. The cost is that a burst can
         * straddle a boundary and briefly double the nominal rate; a short window keeps that small.
         */
        @NotNull
        private Duration rateLimitWindow = Duration.ofSeconds(10);
    }

    /** Common per-channel switches. */
    @Getter
    @Setter
    public abstract static class ChannelSettings {

        /**
         * Whether this channel is wired up at all.
         *
         * <p>The default is supplied by the subclass rather than fixed here, because it differs for a
         * good reason: email works from a host name alone, while chat needs a base URL and webhook
         * needs a signing secret. Defaulting those two to <em>on</em> would mean every deployment that
         * does not use them fails startup on a missing secret it never asked for - so they are off
         * until somebody configures them.
         */
        private boolean enabled;

        protected ChannelSettings(boolean enabledByDefault) {
            this.enabled = enabledByDefault;
        }

        /**
         * Sends permitted per {@link Channels#getRateLimitWindow()}, across every replica.
         *
         * <p>Zero means unlimited, which is the right default for a channel whose provider is inside
         * the cluster. It is the wrong default for anything with a contract behind it.
         */
        @Min(0)
        private int maxPerWindow;

        /** Attempts made inside one dispatch before the delivery is failed and re-queued. */
        @Min(1)
        private int inAttemptRetries = 2;

        @NotNull
        private Duration inAttemptRetryDelay = Duration.ofMillis(200);
    }

    /** SMTP. On by default: a mail host is all it needs, and it is the channel every service uses. */
    @Getter
    @Setter
    public static class Email extends ChannelSettings {

        public Email() {
            super(true);
        }

        @NotBlank
        private String fromAddress = "no-reply@example.internal";

        private String fromDisplayName = "Notifications";

        /** Where a recipient's reply goes, when it should not go to a no-reply mailbox. */
        private String replyToAddress;

        /**
         * Added to every marketing email so a recipient can unsubscribe from their mail client.
         *
         * <p>Not decoration: mailbox providers weigh a missing List-Unsubscribe against the sending
         * domain, and a recipient who cannot find an unsubscribe link reports spam instead, which is
         * far more expensive than the opt-out would have been.
         */
        private String listUnsubscribeUrl;
    }

    /** The internal chat system's HTTP API. Off until a base URL is configured. */
    @Getter
    @Setter
    public static class Chat extends ChannelSettings {

        public Chat() {
            super(false);
        }

        /** Base URL of the chat API. Required whenever the channel is enabled. */
        private String baseUrl;

        /** Bearer token; supplied from Vault through hot reload rather than from a file. */
        private String apiToken;

        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(3);

        /**
         * Read timeout.
         *
         * <p>Deliberately finite and short. A provider that accepts a connection and then never
         * answers is the worst failure mode for a queue: with no read timeout the dispatch thread is
         * gone, the lease expires, the sweeper re-queues the delivery, and the next attempt hangs the
         * same way - until every poll thread is stuck and the queue stops entirely.
         */
        @NotNull
        private Duration readTimeout = Duration.ofSeconds(10);
    }

    /** Outbound HMAC-signed HTTP callbacks. Off until a signing secret is configured. */
    @Getter
    @Setter
    public static class Webhook extends ChannelSettings {

        public Webhook() {
            super(false);
        }

        /**
         * Shared secret the signature is computed with. From Vault, never from a properties file.
         *
         * <p>Required when the channel is enabled - checked at startup, because a webhook channel
         * that silently signs with an empty secret produces signatures every receiver will accept
         * from anyone.
         */
        private String signingSecret;

        @NotBlank
        private String signatureHeader = "X-Ludwig-Signature";

        @NotBlank
        private String timestampHeader = "X-Ludwig-Timestamp";

        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(3);

        @NotNull
        private Duration readTimeout = Duration.ofSeconds(10);
    }

    /** How a recipient's address is found, now that this service no longer stores one. */
    @Getter
    @Setter
    public static class Recipients {

        /**
         * Refuse to write to an address the OIDC provider has not marked verified.
         *
         * <p>On by default. Verification is the provider's job and this service does not second-guess
         * it; what this decides is whether to send to an address nobody has confirmed belongs to the
         * person, which is how a typo in a self-service profile becomes mail to a stranger.
         *
         * <p>"Not verified" means the provider said so. A provider that says nothing at all - an older
         * producer that does not publish the flag - is not treated as a refusal, because that would
         * stop every message in the estate on the day the field was introduced.
         */
        private boolean requireVerifiedContact = true;
    }

    /**
     * Fallbacks for a recipient whose preferences this service does not have.
     *
     * <p>Not "the defaults for everybody": a recipient's own locale, timezone, quiet hours and digest
     * choice are settings owned by the account service and reach this one through a replica. These
     * apply to a literal address with no account behind it, and to a subject whose settings have not
     * arrived yet - a missing preference must not silence a person.
     */
    @Getter
    @Setter
    public static class Preferences {

        /**
         * Where a recipient's stored preferences are read from.
         *
         * <p>{@code AUTO}, the default, uses {@code user-settings-spring-boot-starter} when it is on
         * the classpath and has a mode enabled, and configured defaults otherwise - so adding the
         * dependency is the whole migration.
         *
         * <p>{@code USER_SETTINGS} is the same, except that the service refuses to start if the
         * module is not there and working. Use it once stored preferences are load-bearing: at that
         * point a deployment that silently fell back to defaults would be sending marketing to people
         * who opted out, which is the sort of failure that must not be discovered from a complaint.
         *
         * <p>{@code NONE} pins resolution to configured defaults even where the module is present.
         */
        @NotNull
        private Source source = Source.AUTO;

        @NotBlank
        private String defaultLocale = "en";

        /**
         * Categories a recipient may decline individually.
         *
         * <p>Each becomes a setting definition per channel, declared at startup. A category that is
         * not listed can still be declined through the blanket per-channel opt-out - a marketing
         * category nobody has declared is one nobody has thought about, and the honest behaviour is
         * that "stop sending me things" still covers it.
         *
         * <p>The account service that owns the settings must declare the same categories. That is a
         * contract in code on both sides rather than a string in a table on one, and a key this
         * service reads but the owner never stores simply resolves to its default.
         */
        private List<String> declinableCategories = new ArrayList<>();

        /**
         * Zone quiet hours are evaluated in when a recipient has no timezone of their own.
         *
         * <p>UTC rather than the server's zone, so the behaviour does not change when the deployment
         * moves region - which is the sort of difference nobody notices until a campaign goes out at
         * four in the morning somewhere.
         */
        @NotBlank
        private String defaultTimezone = "UTC";

        private boolean quietHoursEnabled = true;

        /**
         * Whether a marketing notification arriving during quiet hours is deferred or dropped.
         *
         * <p>Deferred. Dropping is simpler and it is silently lossy: the caller was told the request
         * was accepted, and nothing ever arrives.
         */
        private boolean quietHoursDefer = true;

        /**
         * The three ways a deployment can answer "where do preferences come from".
         *
         * <p>Named rather than a boolean because there are genuinely three answers and the third one
         * - "work it out from what is deployed" - is the one almost every environment wants, while
         * production eventually wants the strictness of the second.
         */
        public enum Source {

            /** Use the user-settings module if it is deployed and working; configured defaults if not. */
            AUTO,

            /** The same, but refuse to start when the module is absent or has no mode enabled. */
            USER_SETTINGS,

            /** Configured defaults only, even where the module is present. */
            NONE
        }
    }

    /** Collapsing many notifications for one recipient into one send. */
    @Getter
    @Setter
    public static class Digest {

        private boolean enabled;

        /**
         * Categories that digest instead of sending immediately, mapped to their window length.
         *
         * <p>Per category rather than global: "three people commented on your post" is worth
         * collapsing over an hour, and an invoice is not worth collapsing at all.
         */
        private Map<String, Duration> categories = new LinkedHashMap<>();

        @NotNull
        private Duration runInterval = Duration.ofMinutes(5);

        /** Digest groups collapsed per run, so one run cannot hold the lock indefinitely. */
        @Positive
        private int maxGroupsPerRun = 200;

        @NotBlank
        private String templateKeySuffix = "-digest";
    }

    /** What is kept, and for how long. The documented retention policy is this block. */
    @Getter
    @Setter
    public static class Retention {

        private boolean enabled = true;

        @NotNull
        private Duration runInterval = Duration.ofHours(1);

        /**
         * How long a settled delivery keeps the recipient's address and its rendered body.
         *
         * <p>The shortest window here, and deliberately much shorter than {@link #getDeliveryTtl()}:
         * support needs to see what was sent for as long as a customer might ask about it, which is
         * days, while capacity planning needs to know that deliveries happened, which is months.
         */
        @NotNull
        private Duration recipientDataTtl = Duration.ofDays(7);

        /** How long a rendered body is kept, from the moment it was rendered. */
        @NotNull
        private Duration contentTtl = Duration.ofDays(7);

        /** How long the delivery row itself survives, address already scrubbed. */
        @NotNull
        private Duration deliveryTtl = Duration.ofDays(90);

        /** The audit trail outlives the deliveries it describes, carrying no personal data. */
        @NotNull
        private Duration historyTtl = Duration.ofDays(180);

        /** Rows deleted per purge statement, so one run cannot hold a long transaction open. */
        @Positive
        private int batchSize = 1000;
    }

    /** The leased mutex the maintenance jobs run under. */
    @Getter
    @Setter
    public static class Locks {

        /**
         * How long a lease lasts without a heartbeat.
         *
         * <p>This is the failover time: a pod that dies holding the lock blocks the job for exactly
         * this long. Short enough that a missed digest window is one window, long enough that a
         * garbage-collection pause cannot cost a running job its lock.
         */
        @NotNull
        private Duration lease = Duration.ofMinutes(2);

        /** Renewal interval. Must be comfortably shorter than the lease - validated at startup. */
        @NotNull
        private Duration heartbeat = Duration.ofSeconds(30);
    }

    /** Inbound provider callbacks. */
    @Getter
    @Setter
    public static class Receipts {

        private boolean enabled = true;

        /** Shared secret the inbound signature is verified against. From Vault. */
        private String signingSecret;

        @NotBlank
        private String signatureHeader = "X-Provider-Signature";

        @NotBlank
        private String timestampHeader = "X-Provider-Timestamp";

        /**
         * How far a receipt's timestamp may be from now before it is refused.
         *
         * <p>Without it, a signature captured once is valid forever and can be replayed to suppress
         * an address at any time in the future.
         */
        @NotNull
        private Duration timestampTolerance = Duration.ofMinutes(5);

        /** How long a bounce suppresses an address before it may be retried. Null means forever. */
        private Duration softBounceSuppression = Duration.ofDays(1);
    }

    /** The outbound lifecycle events other services react to. Published through the outbox. */
    @Getter
    @Setter
    public static class Events {

        private boolean enabled = true;

        /** Outbox route name; the destination itself is configured under {@code ludwig.outbox.routes}. */
        @NotBlank
        private String route = "notification-events";
    }
}
