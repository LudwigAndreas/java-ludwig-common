package ru.ludwigandreas.notification.service.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.service.metrics.NotificationMetrics;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.entity.CategoryKind;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.DeliveryPriority;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatus;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.repository.entity.NotificationRequestEntity;
import ru.ludwigandreas.notification.service.channel.ChannelRegistry;
import ru.ludwigandreas.notification.service.model.CategoryClass;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.NotificationCommand;
import ru.ludwigandreas.notification.service.model.RecipientRef;
import ru.ludwigandreas.notification.service.preference.DispatchDecision;
import ru.ludwigandreas.notification.service.preference.PreferenceEvaluator;
import ru.ludwigandreas.notification.service.preference.RecipientPreferences;
import ru.ludwigandreas.notification.service.preference.SuppressionService;
import ru.ludwigandreas.notification.service.recipient.RecipientResolver;
import ru.ludwigandreas.notification.service.recipient.ResolvedRecipient;
import ru.ludwigandreas.notification.service.template.TemplateModel;

/**
 * Turns one accepted request into N delivery rows, each already settled into the state it belongs in.
 *
 * <h2>Why every decision happens here and not at dispatch</h2>
 *
 * <p>Resolution, preferences, quiet hours and the suppression check are all local database reads, so
 * running them inside the ingress transaction costs a few indexed lookups and buys two things worth
 * far more. The caller finds out synchronously how many deliveries their request actually became -
 * which is the difference between a working integration and a silent one - and a suppressed
 * notification never enters the queue at all, so the queue's depth means "work to do" rather than
 * "rows that will turn out to be nothing".
 *
 * <p>The one thing deliberately <em>not</em> done here is rendering. Rendering is CPU work whose
 * result is only needed at send time, it would double the ingress latency, and a template that is
 * about to be corrected would bake the old wording into every queued delivery.
 *
 * <h2>Why {@code ACCEPTED} is a real state</h2>
 *
 * <p>Each delivery is created {@code ACCEPTED} and then transitioned - within this same transaction -
 * to {@code PENDING}, {@code BATCHED}, {@code SUPPRESSED} or {@code DEAD}. Nobody ever observes a
 * delivery sitting in {@code ACCEPTED}, and that is fine: the value is in the status trail, where
 * "created, then suppressed because the recipient had opted out" is a different and much more useful
 * record than a row that simply appeared as suppressed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryFanOutService {

    private final NotificationDeliveryRepository deliveryRepository;
    private final DeliveryStatusRecorder statusRecorder;
    private final RecipientResolver recipientResolver;
    private final PreferenceEvaluator preferenceEvaluator;
    private final SuppressionService suppressionService;
    private final ChannelRegistry channelRegistry;
    private final NotificationProperties properties;
    private final NotificationMetrics metrics;
    private final ObjectMapper objectMapper;

    /**
     * Creates the delivery rows for one request.
     *
     * <p>{@code MANDATORY}: the deliveries, the request and the idempotency claim commit together or
     * not at all. A fan-out that committed independently could leave a request with no deliveries
     * (silently dropped) or deliveries with no request (unattributable), and an at-least-once
     * redelivery would then produce a second, overlapping set.
     *
     * @return every delivery created, whatever state each settled into
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<NotificationDeliveryEntity> fanOut(NotificationRequestEntity request,
                                                   NotificationCommand command) {
        Instant now = Instant.now();
        List<NotificationDeliveryEntity> created = new ArrayList<>();

        for (RecipientRef recipient : command.recipients()) {
            // Once per recipient, not once per recipient per channel. The settings replica is local
            // and cached, so the difference is small today; the reason it is outside the channel loop
            // is that every delivery for this person must be settled against the SAME preferences -
            // one lookup, one answer, snapshotted onto every row it produced.
            RecipientPreferences preferences = recipientResolver.preferences(recipient);

            for (ChannelType channel : command.channels()) {
                if (channelRegistry.find(channel).isEmpty()) {
                    // No bean claims this transport in this deployment. Creating a row that can never
                    // be sent would be a dead letter blaming the recipient for a deployment gap.
                    log.warn("Request {} asked for channel {}, which has no implementation here",
                            request.getId(), channel);
                    continue;
                }
                created.add(create(request, command, recipient, channel, preferences, now));
            }
        }
        return created;
    }

    private NotificationDeliveryEntity create(NotificationRequestEntity request,
                                              NotificationCommand command,
                                              RecipientRef recipient,
                                              ChannelType channel,
                                              RecipientPreferences preferences,
                                              Instant now) {
        Optional<ResolvedRecipient> resolved =
                recipientResolver.resolve(recipient, channel, preferences);

        NotificationDeliveryEntity delivery = resolved
                .map(target -> newDelivery(request, command, recipient, channel, target, now))
                .orElseGet(() -> unresolvedDelivery(request, command, recipient, channel, now));

        // Flushed before the transition so the history row can reference a real delivery id. The
        // history table deliberately has no foreign key, but a trail pointing at a null id would be
        // useless either way.
        delivery = deliveryRepository.saveAndFlush(delivery);
        statusRecorder.recordCreation(delivery);

        if (resolved.isEmpty()) {
            statusRecorder.transition(delivery, DeliveryStatus.DEAD,
                    "no usable " + channel + " address for " + recipient.reference());
            metrics.recordDeliverySuppressed(channel, DispatchDecision.Reasons.UNRESOLVABLE);
            return delivery;
        }

        settle(delivery, resolved.get(), command, now);
        return delivery;
    }

    /**
     * Decides the delivery's initial state: suppressed, deferred, batched for a digest, or ready.
     *
     * <p>Order matters and is not arbitrary. The suppression list is checked before preferences
     * because it is the rule with no bypass - a transactional notification skips preferences and
     * quiet hours, and must still not be sent to an address that hard-bounced.
     */
    private void settle(NotificationDeliveryEntity delivery, ResolvedRecipient recipient,
                        NotificationCommand command, Instant now) {
        if (suppressionService.isSuppressed(recipient.channel(), recipient.address(), now)) {
            delivery.setSuppressionReason(DispatchDecision.Reasons.SUPPRESSION_LIST);
            statusRecorder.transition(delivery, DeliveryStatus.SUPPRESSED,
                    "destination is on the suppression list");
            metrics.recordDeliverySuppressed(recipient.channel(), DispatchDecision.Reasons.SUPPRESSION_LIST);
            return;
        }

        DispatchDecision decision = preferenceEvaluator.evaluate(recipient.preferences(),
                recipient.channel(), command.category(), command.categoryClass(), now);

        if (decision instanceof DispatchDecision.Suppressed suppressed) {
            delivery.setSuppressionReason(suppressed.reason());
            statusRecorder.transition(delivery, DeliveryStatus.SUPPRESSED, suppressed.reason());
            metrics.recordDeliverySuppressed(recipient.channel(), suppressed.reason());
            return;
        }
        if (decision instanceof DispatchDecision.Deferred deferred) {
            // Deferral moves the due time, not the state: the delivery is a perfectly ordinary
            // PENDING row that the claim query will not consider until its window opens.
            //
            // Recorded on the row as well, because "why did this arrive at seven in the morning" has
            // to be answerable from the delivery months later, without a time-travel query against
            // the settings history of a preference that has since changed.
            delivery.setQuietHoursDeferred(true);
            delivery.setNextAttemptAt(deferred.notBefore());
            statusRecorder.transition(delivery, DeliveryStatus.PENDING,
                    "deferred to " + deferred.notBefore() + " (" + deferred.reason() + ")");
            metrics.recordDeliveryEnqueued(recipient.channel(), command.priority());
            return;
        }

        Optional<Duration> digestWindow = digestWindowFor(command);
        if (digestWindow.isPresent() && recipient.userId() != null) {
            batch(delivery, recipient, command, digestWindow.get(), now);
            return;
        }

        statusRecorder.transition(delivery, DeliveryStatus.PENDING, "ready to send");
        metrics.recordDeliveryEnqueued(recipient.channel(), command.priority());
    }

    /**
     * Holds the delivery for its digest window instead of sending it now.
     *
     * <p>The group key is recipient + channel + window, so two notifications for one person in the
     * same hour collapse and two for different people never do. The window start is computed by
     * truncating the current instant to the window length rather than by taking "now plus the
     * window" - otherwise every notification would start its own window and nothing would ever
     * collapse with anything.
     */
    private void batch(NotificationDeliveryEntity delivery, ResolvedRecipient recipient,
                       NotificationCommand command, Duration window, Instant now) {
        long windowMillis = Math.max(1L, window.toMillis());
        Instant windowStart = Instant.ofEpochMilli(now.toEpochMilli() / windowMillis * windowMillis);
        Instant windowEnd = windowStart.plusMillis(windowMillis);

        delivery.setDigestGroup(recipient.userId() + "|" + recipient.channel() + "|" + windowStart);
        delivery.setNextAttemptAt(windowEnd);
        statusRecorder.transition(delivery, DeliveryStatus.BATCHED,
                "batched for the digest window closing at " + windowEnd);
        metrics.recordDeliveryEnqueued(recipient.channel(), command.priority());
    }

    /**
     * Whether this category digests, and over what window.
     *
     * <p>A transactional notification never digests, whatever the configuration says. Collapsing a
     * password reset into an hourly summary would be the single most damaging thing this feature
     * could do, so the guard is here rather than left to whoever edits the category list.
     */
    private Optional<Duration> digestWindowFor(NotificationCommand command) {
        NotificationProperties.Digest digest = properties.getDigest();
        if (!digest.isEnabled() || command.categoryClass() == CategoryClass.TRANSACTIONAL) {
            return Optional.empty();
        }
        return Optional.ofNullable(digest.getCategories().get(command.category()));
    }

    private NotificationDeliveryEntity newDelivery(NotificationRequestEntity request,
                                                   NotificationCommand command,
                                                   RecipientRef recipient,
                                                   ChannelType channel,
                                                   ResolvedRecipient target,
                                                   Instant now) {
        return baseDelivery(request, command, recipient, channel, now)
                .recipientUserId(target.userId())
                .recipientAddress(target.address())
                .recipientLocale(target.locale().toLanguageTag())
                .recipientTimezone(target.zone().getId())
                .variables(variables(command, recipient, target))
                .tenantId(target.tenantId() == null ? request.getTenantId() : target.tenantId())
                .build();
    }

    /**
     * A delivery for a recipient that could not be resolved.
     *
     * <p>It still gets a row. That is the whole reason request and delivery are separate aggregates:
     * a request to five people where one has no email address must produce four sends and one
     * recorded, explicable failure - not a rejected request, and not four sends and silence about
     * the fifth.
     */
    private NotificationDeliveryEntity unresolvedDelivery(NotificationRequestEntity request,
                                                          NotificationCommand command,
                                                          RecipientRef recipient,
                                                          ChannelType channel,
                                                          Instant now) {
        return baseDelivery(request, command, recipient, channel, now)
                .recipientUserId(recipient.userId())
                .recipientLocale(properties.getPreferences().getDefaultLocale())
                .recipientTimezone(properties.getPreferences().getDefaultTimezone())
                .variables(variables(command, recipient, null))
                .tenantId(request.getTenantId())
                .build();
    }

    private NotificationDeliveryEntity.NotificationDeliveryEntityBuilder baseDelivery(
            NotificationRequestEntity request, NotificationCommand command, RecipientRef recipient,
            ChannelType channel, Instant now) {
        Instant due = command.scheduledAt() == null || command.scheduledAt().isBefore(now)
                ? now
                : command.scheduledAt();
        return NotificationDeliveryEntity.builder()
                .requestId(request.getId())
                .channel(ChannelKind.valueOf(channel.name()))
                .templateKey(command.templateKey())
                .category(command.category())
                .categoryKind(CategoryKind.valueOf(command.categoryClass().name()))
                .priority(DeliveryPriority.valueOf(command.priority().name()))
                .status(DeliveryStatus.ACCEPTED)
                .attempts(0)
                // Snapshotted rather than read from configuration at retry time, so raising the limit
                // cannot revive deliveries that already gave up - and lowering it cannot strand
                // in-flight ones above their new ceiling.
                .maxAttempts(properties.getRetry().getMaxAttempts())
                .nextAttemptAt(due)
                .scheduledAt(command.scheduledAt())
                .correlationId(request.getCorrelationId())
                .dedupKey(dedupKey(request, recipient, channel));
    }

    /**
     * The variable map this delivery will render with.
     *
     * <p>Three layers, most specific last: the request's variables, then anything the caller
     * addressed to this recipient in particular, then the resolved recipient's own context under a
     * {@code recipient} key. The nesting is what keeps the layers from colliding - a caller sending a
     * variable called {@code locale} would otherwise silently overwrite the one the renderer relies
     * on, and the symptom would be a template formatting dates in the wrong language.
     */
    private String variables(NotificationCommand command, RecipientRef recipient,
                             ResolvedRecipient target) {
        Map<String, Object> merged = new LinkedHashMap<>(command.variables());
        merged.putAll(recipient.variables());
        merged.put(TemplateModel.RECIPIENT, TemplateModel.recipientContext(
                target == null ? recipient.userId() : target.userId(),
                target == null ? null : target.displayName(),
                target == null
                        ? properties.getPreferences().getDefaultLocale()
                        : target.locale().toLanguageTag(),
                target == null
                        ? properties.getPreferences().getDefaultTimezone()
                        : target.zone().getId()));
        try {
            return objectMapper.writeValueAsString(merged);
        } catch (JacksonException e) {
            // The request's variables round-tripped through JSON on the way in, so this can only be
            // a per-recipient value a caller built by hand. Failing the whole fan-out would punish
            // every other recipient, so this delivery renders with the request-wide set alone.
            log.warn("Per-recipient variables for {} could not be serialized; using the request's",
                    recipient.reference(), e);
            return request(command);
        }
    }

    private String request(NotificationCommand command) {
        try {
            return objectMapper.writeValueAsString(command.variables());
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Request variables could not be serialized", e);
        }
    }

    /**
     * The per-delivery uniqueness key, backed by a unique index.
     *
     * <p>The last line of defence against a double send. The request-level idempotency claim should
     * already have stopped a duplicate, but "should" is not a guarantee across a database failover or
     * a bug in a caller's key generation - and this one is enforced by the database on the row that
     * actually causes a message to be sent.
     *
     * <p>Falls back to the request id when the caller supplied no key, which keeps the column always
     * populated and therefore always indexed, at the cost of making the constraint a no-op for
     * callers who opted out of idempotency in the first place.
     *
     * <p>Built from {@link RecipientRef#dedupToken()} rather than the readable reference: the key
     * outlives the address by eighty-three days, so embedding the address would defeat the retention
     * scrub, and the masked form is lossy enough that two recipients could collide.
     */
    private String dedupKey(NotificationRequestEntity request, RecipientRef recipient, ChannelType channel) {
        String base = request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()
                ? request.getId().toString()
                : request.getSource() + ":" + request.getIdempotencyKey();
        return base + "|" + channel + "|" + recipient.dedupToken();
    }
}
