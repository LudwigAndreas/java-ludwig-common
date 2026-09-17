package ru.ludwigandreas.notification.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.repository.DeliveryContentRepository;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatus;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.repository.query.DeliverySearchCriteria;
import ru.ludwigandreas.notification.service.exception.DeliveryContentUnavailableException;
import ru.ludwigandreas.notification.service.exception.DeliveryNotFoundException;
import ru.ludwigandreas.notification.service.exception.IllegalDeliveryTransitionException;
import ru.ludwigandreas.notification.service.mapper.NotificationEntityMapper;
import ru.ludwigandreas.notification.service.model.DeliveryContentView;
import ru.ludwigandreas.notification.service.model.DeliveryQuery;
import ru.ludwigandreas.notification.service.model.DeliveryTransition;
import ru.ludwigandreas.notification.service.model.DeliveryView;
import ru.ludwigandreas.notification.service.queue.DeliveryStatusRecorder;
import ru.ludwigandreas.security.data.DataAccessGuard;
import ru.ludwigandreas.security.data.DataAction;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.SecurityPrincipals;

/**
 * The operator-facing half of the service: look at what happened, and change what has not happened
 * yet.
 *
 * <h2>Two levels of authorization, and why both</h2>
 *
 * <p>The controller's {@code @PreAuthorize} decides whether this caller may use these endpoints at
 * all. That is not enough on its own, because it says nothing about <em>which</em> deliveries - so
 * the search query has the caller's data scope ANDed into its {@code WHERE} clause (see
 * {@code DeliveryQueryRepositoryImpl}), and every single-object load here goes through
 * {@link DataAccessGuard#check}.
 *
 * <p>The guard is needed precisely because a load by id does not go through the scoped query: without
 * it, a caller who guessed or was shown a delivery id could read any tenant's delivery. It is applied
 * after the row is loaded, because a scope is a statement about a row's contents, and the guard
 * audits its own denials.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryAdminService {

    /** The name this resource's scope mapping and policies are registered under. */
    private static final String RESOURCE_TYPE = "notification-delivery";

    private final NotificationDeliveryRepository deliveryRepository;
    private final DeliveryContentRepository contentRepository;
    private final DeliveryStatusRecorder statusRecorder;
    private final NotificationEntityMapper mapper;
    private final DataAccessGuard dataAccessGuard;

    /**
     * Translates the business layer's query into the repository's criteria.
     *
     * <p>The translation happens here rather than in the controller so that the persistence layer's
     * own types never appear in a web signature - which is what the layering rules check, and what
     * stops the next change from passing an entity out through the same door.
     */
    @Transactional(readOnly = true)
    public Page<DeliveryView> search(DeliveryQuery query) {
        DeliverySearchCriteria criteria =
                new DeliverySearchCriteria(query.filter(), query.orderBy(), query.top(), query.skip());
        return deliveryRepository.search(criteria).map(mapper::toView);
    }

    @Transactional(readOnly = true)
    public DeliveryView get(UUID id) {
        return mapper.toView(require(id, DataAction.READ));
    }

    @Transactional(readOnly = true)
    public List<DeliveryTransition> history(UUID id) {
        require(id, DataAction.READ);
        return mapper.toTransitions(deliveryRepository.historyOf(id));
    }

    /**
     * Returns what was actually sent.
     *
     * <p>The single most sensitive read this service offers, so it is a separate endpoint with its
     * own authorization rather than a field on the delivery listing - and the access is logged here,
     * naming the operator, because "support read what we sent that customer" is exactly the sort of
     * access an audit trail exists for.
     */
    @Transactional(readOnly = true)
    public DeliveryContentView content(UUID id) {
        NotificationDeliveryEntity delivery = require(id, DataAction.READ);
        log.info("Operator {} read the rendered content of delivery {}",
                SecurityPrincipals.current().map(LudwigPrincipal::subject).orElse("unknown"), id);
        return contentRepository.findById(id)
                .map(content -> mapper.toView(content, delivery.getTemplateVersion()))
                .orElseThrow(() -> new DeliveryContentUnavailableException(id));
    }

    /**
     * Puts a dead delivery back in the queue.
     *
     * <p>Only from {@code DEAD}. Retrying a {@code SENT} delivery would send the message twice, and
     * retrying a {@code SUPPRESSED} one would override an opt-out by hand - so the guard is on the
     * state rather than on the operator's judgement.
     *
     * <p>The attempt counter is reset. A dead delivery is being retried because the <em>cause</em> has
     * been fixed - a corrected template, a restored provider, a repaired credential - so making it
     * fight for the last one of its eight original attempts would mean a single further blip
     * dead-letters it again immediately.
     */
    @Transactional
    public DeliveryView retry(UUID id) {
        NotificationDeliveryEntity delivery = require(id, DataAction.WRITE);
        if (delivery.getStatus() != DeliveryStatus.DEAD) {
            throw new IllegalDeliveryTransitionException(id, delivery.getStatus().name(), "retry");
        }
        delivery.setAttempts(0);
        delivery.setNextAttemptAt(Instant.now());
        delivery.setClaimedAt(null);
        delivery.setClaimedBy(null);
        delivery.setSettledAt(null);
        statusRecorder.transition(delivery, DeliveryStatus.PENDING,
                "requeued by " + SecurityPrincipals.current().map(LudwigPrincipal::subject).orElse("operator"));
        log.info("Delivery {} was requeued from DEAD", id);
        return mapper.toView(delivery);
    }

    /**
     * Stops a delivery that has not gone out.
     *
     * <p>{@code PENDING}, {@code FAILED} and {@code BATCHED} can be cancelled. {@code CLAIMED}
     * deliberately cannot: a claimed delivery may be inside a provider call at this very moment, and
     * "cancelled" would be a claim about the world that this service cannot make good on. An operator
     * who needs to stop those disables the channel, which the poller honours on its next cycle.
     */
    @Transactional
    public DeliveryView cancel(UUID id) {
        NotificationDeliveryEntity delivery = require(id, DataAction.WRITE);
        boolean cancellable = switch (delivery.getStatus()) {
            case PENDING, FAILED, BATCHED -> true;
            default -> false;
        };
        if (!cancellable) {
            throw new IllegalDeliveryTransitionException(id, delivery.getStatus().name(), "cancel");
        }
        statusRecorder.transition(delivery, DeliveryStatus.CANCELLED,
                "cancelled by " + SecurityPrincipals.current().map(LudwigPrincipal::subject).orElse("operator"));
        log.info("Delivery {} was cancelled", id);
        return mapper.toView(delivery);
    }

    /**
     * Loads a delivery and checks the caller may act on it.
     *
     * <p>The read/write distinction is passed in rather than collapsed into one "access" check,
     * because they are genuinely different grants: a support role reads every delivery in its tenant
     * and changes none of them, and a single check could only ever enforce the wider of the two.
     */
    private NotificationDeliveryEntity require(UUID id, String action) {
        NotificationDeliveryEntity delivery = deliveryRepository.findById(id)
                .orElseThrow(() -> new DeliveryNotFoundException(id));
        dataAccessGuard.check(RESOURCE_TYPE, action, delivery, NotificationDeliveryEntity::getId);
        return delivery;
    }
}
