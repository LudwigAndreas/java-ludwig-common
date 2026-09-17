package ru.ludwigandreas.notification.service.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.service.metrics.NotificationMetrics;
import ru.ludwigandreas.notification.repository.entity.DeliveryPriority;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.service.Pii;
import ru.ludwigandreas.notification.service.channel.ChannelRegistry;
import ru.ludwigandreas.notification.service.channel.ChannelRuntime;
import ru.ludwigandreas.notification.service.channel.NotificationChannel;
import ru.ludwigandreas.notification.service.exception.TemplateNotFoundException;
import ru.ludwigandreas.notification.service.exception.TemplateRenderException;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.DeliveryResult;
import ru.ludwigandreas.notification.service.model.FailureClass;
import ru.ludwigandreas.notification.service.model.RenderedNotification;
import ru.ludwigandreas.notification.service.preference.DispatchDecision;
import ru.ludwigandreas.notification.service.preference.SuppressionService;
import ru.ludwigandreas.notification.service.template.RenderedTemplate;
import ru.ludwigandreas.notification.service.template.TemplateCoordinates;
import ru.ludwigandreas.notification.service.template.TemplateRenderer;
import ru.ludwigandreas.notification.service.template.TemplateRevisionService;
import ru.ludwigandreas.common.retryer.RetryPolicy;
import ru.ludwigandreas.common.retryer.RetryingCall;
import ru.ludwigandreas.observability.correlation.CorrelationContext;

/**
 * One poll cycle: reserve, claim, render, send, record.
 *
 * <h2>Transaction discipline - the rule this class exists to enforce</h2>
 *
 * <p>This class carries no {@code @Transactional} annotation at all, and that is not an oversight.
 * Every database interaction is delegated to a collaborator that owns its own short transaction
 * ({@link DeliveryClaimService}, {@link DeliveryOutcomeRecorder}, {@link ChannelRateLimiter},
 * {@link SuppressionService}), and the provider call in {@link #dispatchOne} happens between them
 * with nothing open.
 *
 * <p>The failure mode being avoided is specific and expensive. A transaction that spans a provider
 * call holds a pooled database connection for the duration of somebody else's network, so a single
 * SMTP relay that stops answering drains the connection pool - and then every other part of the
 * service, including the ingress that has nothing to do with SMTP, starts failing to get a
 * connection. The queue is supposed to isolate a slow provider; a transaction wrapped around the
 * send would make it the fastest route to a total outage.
 *
 * <h2>Priority lanes</h2>
 *
 * <p>Two claims per channel per cycle. The first is restricted to {@code HIGH} and takes at most the
 * configured reserve; the second takes the remainder at any priority. Ordering by priority alone
 * would not be enough: a bulk backlog of a hundred thousand rows is also, eventually, the
 * <em>oldest</em> work, and one query ordered by priority then age still spends the whole batch on it
 * whenever no high-priority row happens to be due at that instant. A reserved pass guarantees the
 * capacity rather than hoping for it.
 *
 * <h2>Behaviour at three replicas</h2>
 *
 * <p>Every replica runs this loop independently and they do not coordinate. {@code SKIP LOCKED}
 * makes the claims disjoint, the rate limiter's counter is shared so the three of them together
 * honour one limit, and the outcome of each delivery is written by whichever replica claimed it. No
 * distributed lock is involved and none is wanted - it would serialise the one part of this service
 * that scales horizontally for free.
 */
@Slf4j
public class DeliveryDispatchService {

    /** The shape a delivery's stored variable map deserializes into. */
    private static final TypeReference<Map<String, Object>> VARIABLE_MAP = new TypeReference<>() { };

    private final DeliveryClaimService claimService;
    private final DeliveryOutcomeRecorder outcomeRecorder;
    private final ChannelRateLimiter rateLimiter;
    private final ChannelRegistry channelRegistry;
    private final ChannelRuntime channelRuntime;
    private final TemplateRenderer templateRenderer;
    private final TemplateRevisionService templateRevisionService;
    private final SuppressionService suppressionService;
    private final CorrelationContext correlationContext;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;
    private final ObjectMapper objectMapper;
    private final String owner;

    /**
     * Leases this instance currently holds, so a graceful shutdown can hand them back.
     *
     * <p>Final field over a concurrent set: the stale sweeper would recover these eventually, but
     * "eventually" is one lease timeout - minutes, because it has to exceed the slowest legitimate
     * dispatch - and a rolling deploy would otherwise add that delay to every delivery in flight.
     */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    @SuppressWarnings("checkstyle:ParameterNumber")
    public DeliveryDispatchService(DeliveryClaimService claimService,
                                   DeliveryOutcomeRecorder outcomeRecorder,
                                   ChannelRateLimiter rateLimiter,
                                   ChannelRegistry channelRegistry,
                                   ChannelRuntime channelRuntime,
                                   TemplateRenderer templateRenderer,
                                   TemplateRevisionService templateRevisionService,
                                   SuppressionService suppressionService,
                                   CorrelationContext correlationContext,
                                   NotificationProperties properties,
                                   NotificationMetrics metrics,
                                   ObjectMapper objectMapper,
                                   String owner) {
        this.claimService = claimService;
        this.outcomeRecorder = outcomeRecorder;
        this.rateLimiter = rateLimiter;
        this.channelRegistry = channelRegistry;
        this.channelRuntime = channelRuntime;
        this.templateRenderer = templateRenderer;
        this.templateRevisionService = templateRevisionService;
        this.suppressionService = suppressionService;
        this.correlationContext = correlationContext;
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.owner = owner;
    }

    /** @return how many deliveries were dispatched across every registered channel */
    public int processCycle() {
        int dispatched = 0;
        for (ChannelType channel : channelRegistry.registered()) {
            dispatched += processChannel(channel);
        }
        return dispatched;
    }

    private int processChannel(ChannelType channel) {
        if (!channelRuntime.isEnabled(channel)) {
            // Queued deliveries wait rather than fail. Turning a channel off during a provider
            // incident must not dead-letter everything already enqueued for it.
            return 0;
        }

        Instant now = Instant.now();
        int budget = properties.getQueue().getBatchSize();
        int granted = rateLimiter.reserve(channel, budget, now);
        if (granted <= 0) {
            metrics.recordRateLimited(channel);
            return 0;
        }

        List<NotificationDeliveryEntity> claimed = claim(channel, granted, now);
        // Permits reserved but not matched by work are returned immediately, so a quiet channel does
        // not spend its window on empty batches.
        rateLimiter.release(channel, granted - claimed.size(), now);

        if (claimed.isEmpty()) {
            return 0;
        }
        metrics.recordClaim(channel, claimed.size());
        claimed.forEach(delivery -> inFlight.add(delivery.getId()));

        try {
            return dispatchAll(channel, claimed, now);
        } finally {
            claimed.forEach(delivery -> inFlight.remove(delivery.getId()));
        }
    }

    /**
     * The two-pass claim that makes the lanes real.
     *
     * <p>The high pass runs first and is capped at the reserve, so it can never consume the whole
     * budget when there is little high-priority work; the general pass then fills whatever is left
     * with anything due, ordered by priority.
     */
    private List<NotificationDeliveryEntity> claim(ChannelType channel, int granted, Instant now) {
        List<NotificationDeliveryEntity> claimed = new ArrayList<>();
        int reserve = Math.min(properties.getQueue().getHighPriorityReserve(), granted);
        if (reserve > 0) {
            claimed.addAll(claimService.claim(channel, DeliveryPriority.HIGH, reserve, now, owner));
        }
        int remaining = granted - claimed.size();
        if (remaining > 0) {
            claimed.addAll(claimService.claim(channel, DeliveryPriority.BULK, remaining, now, owner));
        }
        return claimed;
    }

    private int dispatchAll(ChannelType channel, List<NotificationDeliveryEntity> claimed, Instant now) {
        // One batched suppression query rather than one per delivery. A recipient can unsubscribe in
        // the minutes a delivery spends behind a backoff, so the fan-out check is not sufficient -
        // but asking per row would be a hundred round trips on the hottest path in the service.
        Set<String> addresses = new LinkedHashSet<>();
        claimed.forEach(delivery -> {
            if (delivery.getRecipientAddress() != null) {
                addresses.add(delivery.getRecipientAddress());
            }
        });
        Set<String> suppressed = suppressionService.suppressedAmong(channel, addresses, now);

        NotificationChannel sender = channelRegistry.require(channel);
        int sent = 0;
        for (NotificationDeliveryEntity delivery : claimed) {
            if (dispatchOne(sender, delivery, suppressed)) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * Render, send, record - for one delivery, with the correlation id bound for the duration.
     *
     * <p>Binding the id here is what makes the brief's requirement real: the delivery happens minutes later,
     * on a different thread, in a different pod from the request that asked for it, so without
     * re-binding the id from the row every log line about this send would be unjoinable to the caller
     * who triggered it. The scope is closed in a {@code try}-with-resources because the thread is
     * pooled and a leaked binding would attribute the next delivery's lines to this one.
     *
     * @return whether the provider accepted it
     */
    private boolean dispatchOne(NotificationChannel sender, NotificationDeliveryEntity delivery,
                                Set<String> suppressed) {
        try (CorrelationContext.Scope ignored = correlationContext.open(delivery.getCorrelationId())) {
            if (delivery.getRecipientAddress() == null
                    || suppressed.contains(Pii.normalizeAddress(delivery.getRecipientAddress()))) {
                outcomeRecorder.recordSuppressed(delivery.getId(),
                        DispatchDecision.Reasons.SUPPRESSION_LIST);
                return false;
            }

            RenderedNotification message = render(delivery);
            if (message == null) {
                return false;
            }
            return send(sender, delivery, message);
        } catch (RuntimeException e) {
            // Nothing may escape one delivery and abort the batch: the remaining rows are already
            // leased by this instance, and abandoning them would leave them CLAIMED until the sweeper
            // ran. Recorded as retryable because an unclassified failure here is a bug or a blip
            // rather than a provider's permanent refusal.
            log.error("Delivery {} failed unexpectedly", delivery.getId(), e);
            outcomeRecorder.recordFailure(delivery.getId(),
                    "unexpected failure: " + e.getClass().getSimpleName(), FailureClass.RETRYABLE);
            return false;
        }
    }

    /**
     * Renders, or dead-letters the delivery if the template cannot produce a message.
     *
     * <p>A render failure is <em>terminal</em>, and deliberately so. The variables were fixed when
     * the request was accepted and the template is what it is - so retrying a missing variable eight
     * times over two hours produces eight identical failures and delays the operator learning about
     * it. A template that was genuinely being edited at that instant is the one false positive, and
     * the delivery can be replayed from the admin API once it is fixed.
     *
     * @return the message, or null when the delivery has been dead-lettered
     */
    private RenderedNotification render(NotificationDeliveryEntity delivery) {
        ChannelType channel = ChannelType.valueOf(delivery.getChannel().name());
        Instant start = Instant.now();
        RenderedTemplate rendered;
        try {
            rendered = templateRenderer.render(
                    new TemplateCoordinates(delivery.getTemplateKey(), channel,
                            Locale.forLanguageTag(delivery.getRecipientLocale())),
                    variablesOf(delivery));
        } catch (TemplateNotFoundException | TemplateRenderException e) {
            outcomeRecorder.recordFailure(delivery.getId(), e.getMessage(), FailureClass.TERMINAL);
            return null;
        }
        metrics.recordRenderDuration(channel, Duration.between(start, Instant.now()));

        templateRevisionService.recordUsage(rendered.templateName(), rendered.templateVersion(),
                rendered.templateSource());
        outcomeRecorder.recordRendered(delivery.getId(), rendered);

        return new RenderedNotification(
                delivery.getId(),
                channel,
                delivery.getRecipientAddress(),
                delivery.getRecipientLocale(),
                rendered.subject(),
                rendered.htmlBody(),
                rendered.textBody(),
                delivery.getTemplateKey(),
                delivery.getCategory(),
                Map.of(),
                delivery.getCorrelationId());
    }

    /**
     * The one call in this class that touches the network, and the one with no transaction open.
     *
     * <h2>The in-attempt retry, and why it is so narrow</h2>
     *
     * <p>{@code common-utils}' {@link RetryingCall} retries the provider call a couple of times
     * before the delivery is failed and re-queued - but <em>only</em> when the failure was a refused
     * connection. That restriction is the whole of the design here.
     *
     * <p>Retrying a send in general is unsafe: a read timeout means the request was transmitted and
     * the answer was not received, so the provider may well have accepted it, and retrying sends the
     * message twice. A {@link java.net.ConnectException} is the one failure that proves the opposite -
     * the connection was never established, so nothing was transmitted and nothing can have been
     * accepted. Retrying that is free, and it covers the common case of a relay behind a load
     * balancer restarting one backend.
     *
     * <p>Everything else falls through to the queue's own retry, which has backoff, jitter, a budget
     * and a record - all of which an in-call retry lacks.
     */
    private boolean send(NotificationChannel sender, NotificationDeliveryEntity delivery,
                         RenderedNotification message) {
        ChannelType channel = message.channel();
        Instant start = Instant.now();
        DeliveryResult result;
        try {
            result = RetryingCall.with(connectionRetryPolicy(channel)).call(() -> sender.send(message));
        } catch (RuntimeException e) {
            log.warn("Channel {} threw for delivery {} to {}", sender.name(), delivery.getId(),
                    Pii.address(message.address()), e);
            result = new DeliveryResult.Failed("channel threw: " + e.getClass().getSimpleName(),
                    ru.ludwigandreas.notification.service.channel.HttpFailureClassifier.classify(e));
        }
        metrics.recordSendDuration(channel, Duration.between(start, Instant.now()));

        if (result instanceof DeliveryResult.Sent accepted) {
            outcomeRecorder.recordSent(delivery.getId(), accepted.providerMessageId());
            log.info("Delivered {} on {} to {}", delivery.getId(), channel,
                    Pii.address(message.address()));
            return true;
        }
        DeliveryResult.Failed failed = (DeliveryResult.Failed) result;
        outcomeRecorder.recordFailure(delivery.getId(), failed.reason(), failed.failureClass());
        return false;
    }

    /**
     * Retries only a refused connection, and only a couple of times.
     *
     * <p>Built per call rather than held as a field: {@link RetryPolicy} is mutable, and one shared
     * instance mutated by a configuration reload while another thread is reading it is the kind of
     * bug that appears once a month and never reproduces.
     */
    private RetryPolicy connectionRetryPolicy(ChannelType channel) {
        return new RetryPolicy()
                .withMaxRetries(channelRuntime.inAttemptRetries(channel))
                .withDelay(channelRuntime.inAttemptRetryDelay(channel).toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS)
                .retryIf(DeliveryDispatchService::isConnectionRefused);
    }

    /**
     * Whether the failure proves nothing was transmitted.
     *
     * <p>Walks the cause chain, because the connect failure arrives wrapped - a
     * {@code ResourceAccessException} around a {@code ConnectException}, or a JavaMail exception
     * around one.
     */
    private static boolean isConnectionRefused(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.ConnectException) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }

    /**
     * The variables a delivery renders with, read off its own row.
     *
     * <p>Never from the parent request: the poller runs at batch scale, and one extra select per
     * delivery would double its database traffic for data that cannot change after fan-out - and the
     * per-recipient layer would not be there anyway.
     *
     * <p>An unreadable map dead-letters the delivery rather than rendering an empty one, because a
     * template rendered with no variables either fails strictly (fine) or, for a template with
     * defaults everywhere, produces a message with every personalisation missing (not fine).
     */
    private Map<String, Object> variablesOf(NotificationDeliveryEntity delivery) {
        try {
            return objectMapper.readValue(delivery.getVariables(), VARIABLE_MAP);
        } catch (java.io.IOException e) {
            throw new TemplateRenderException(delivery.getTemplateKey(),
                    "stored variables could not be read: " + e.getMessage(), e);
        }
    }

    /** Leases this instance currently holds - used by the shutdown drain. */
    public Set<UUID> inFlightLeases() {
        return Set.copyOf(inFlight);
    }
}
