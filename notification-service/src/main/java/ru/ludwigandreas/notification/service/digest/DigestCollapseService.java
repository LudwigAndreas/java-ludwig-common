package ru.ludwigandreas.notification.service.digest;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatus;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.service.queue.DeliveryStatusRecorder;

/**
 * Folds a window's worth of batched notifications for one recipient into a single digest send.
 *
 * <h2>Why this needs the distributed lock and the poller does not</h2>
 *
 * <p>The delivery poller is partitioned by {@code SKIP LOCKED}: three replicas claim disjoint rows,
 * so running it everywhere is not merely safe but the point. Collapsing is not partitioned. Three
 * replicas that each read a digest group, build a digest and mark the members collapsed produce three
 * digests for one person - or, worse, interleave so that some members are marked against one digest
 * and some against another. There is no {@code SKIP LOCKED} trick that fixes it, because the work is
 * defined over a <em>set</em> of rows rather than over each row independently.
 *
 * <p>So the collapse runs under {@code notification-digest}, on exactly one replica per schedule.
 *
 * <h2>How a collapse is represented</h2>
 *
 * <p>One new {@code PENDING} delivery carrying the digest template, plus the original members moved
 * to {@code COLLAPSED} with a pointer to it. The members are not deleted and not marked
 * {@code CANCELLED}: a recipient asking "did you ever tell me about X?" is answered by a row that
 * says X was folded into a digest that was sent, and "cancelled" would be a lie about what happened.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigestCollapseService {

    /** Variable the digest template iterates over to list what it is summarising. */
    private static final String ITEMS_VARIABLE = "items";

    /** Variable carrying how many notifications were folded in, for a "and 12 more" line. */
    private static final String COUNT_VARIABLE = "itemCount";

    private final NotificationDeliveryRepository deliveryRepository;
    private final DeliveryStatusRecorder statusRecorder;
    private final NotificationProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Collapses one digest group.
     *
     * <p>One transaction per group rather than one for the whole run: a run collapses up to a few
     * hundred groups, and a single failure in the middle must not undo the ones already done - they
     * would be redone on the next run, and the members would by then be pointing at a digest delivery
     * that no longer exists.
     *
     * @return the digest delivery created, or empty when the group turned out to hold nothing
     *         (another replica took it, or its members were cancelled in the meantime)
     */
    @Transactional
    public java.util.Optional<NotificationDeliveryEntity> collapse(String digestGroup, Instant now) {
        List<NotificationDeliveryEntity> members = deliveryRepository.dueBatchedIn(digestGroup, now);
        if (members.isEmpty()) {
            return java.util.Optional.empty();
        }

        NotificationDeliveryEntity first = members.get(0);
        NotificationDeliveryEntity digest = deliveryRepository.saveAndFlush(buildDigest(first, members, now));
        statusRecorder.recordCreation(digest);
        statusRecorder.transition(digest, DeliveryStatus.PENDING,
                "digest of " + members.size() + " notification(s) in group " + digestGroup);

        for (NotificationDeliveryEntity member : members) {
            member.setCollapsedIntoId(digest.getId());
            statusRecorder.transition(member, DeliveryStatus.COLLAPSED,
                    "folded into digest delivery " + digest.getId());
        }

        log.info("Collapsed {} notification(s) in group {} into digest delivery {}",
                members.size(), digestGroup, digest.getId());
        return java.util.Optional.of(digest);
    }

    /**
     * The digest delivery itself.
     *
     * <p>Built from the first member, which carries the recipient, channel, locale and timezone that
     * every member of a group shares by construction - the group key is exactly recipient, channel
     * and window.
     *
     * <p>Its priority is the <em>highest</em> among the members rather than the first one's. A digest
     * is only ever built from marketing categories, so this rarely matters, but taking the maximum is
     * the rule that cannot surprise anybody: a collapse must never make something slower than it
     * would have been on its own.
     */
    private NotificationDeliveryEntity buildDigest(NotificationDeliveryEntity first,
                                                   List<NotificationDeliveryEntity> members,
                                                   Instant now) {
        return NotificationDeliveryEntity.builder()
                .requestId(first.getRequestId())
                .channel(first.getChannel())
                .recipientUserId(first.getRecipientUserId())
                .recipientAddress(first.getRecipientAddress())
                .recipientLocale(first.getRecipientLocale())
                .recipientTimezone(first.getRecipientTimezone())
                .templateKey(first.getCategory() + properties.getDigest().getTemplateKeySuffix())
                .category(first.getCategory())
                .categoryKind(first.getCategoryKind())
                .priority(members.stream()
                        .map(NotificationDeliveryEntity::getPriority)
                        .max(java.util.Comparator.comparingInt(
                                ru.ludwigandreas.notification.repository.entity.DeliveryPriority::getWeight))
                        .orElse(first.getPriority()))
                .status(DeliveryStatus.ACCEPTED)
                .attempts(0)
                .maxAttempts(properties.getRetry().getMaxAttempts())
                .nextAttemptAt(now)
                .variables(digestVariables(members))
                .correlationId(first.getCorrelationId())
                .tenantId(first.getTenantId())
                // Deliberately distinct from any member's key, so a re-run that somehow reached this
                // point twice collides on the unique index rather than sending two digests.
                .dedupKey("digest|" + first.getDigestGroup())
                .build();
    }

    /**
     * The digest's variable map: each member's own variables, in order, under one list.
     *
     * <p>A digest template therefore iterates rather than being told a shape - {@code <#list items as
     * item>} - which is what lets one template summarise notifications this service has never seen.
     */
    private String digestVariables(List<NotificationDeliveryEntity> members) {
        List<Object> items = new ArrayList<>(members.size());
        for (NotificationDeliveryEntity member : members) {
            items.add(readVariables(member));
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put(ITEMS_VARIABLE, items);
        variables.put(COUNT_VARIABLE, items.size());
        // The recipient context of the first member: every member of a group shares a recipient, so
        // one copy is correct and carrying all of them would only make the map larger.
        Object recipient = readVariables(members.get(0)).get("recipient");
        if (recipient != null) {
            variables.put("recipient", recipient);
        }
        try {
            return objectMapper.writeValueAsString(variables);
        } catch (JacksonException e) {
            throw new IllegalStateException("Digest variables could not be serialized", e);
        }
    }

    private Map<String, Object> readVariables(NotificationDeliveryEntity member) {
        try {
            return objectMapper.readValue(member.getVariables(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (java.io.IOException e) {
            // One unreadable member must not sink the digest for the other eleven; it contributes an
            // empty entry, which a template rendering `items` will simply show as a blank line.
            log.warn("Delivery {} has unreadable variables; it contributes nothing to its digest",
                    member.getId(), e);
            return Map.of();
        }
    }
}
