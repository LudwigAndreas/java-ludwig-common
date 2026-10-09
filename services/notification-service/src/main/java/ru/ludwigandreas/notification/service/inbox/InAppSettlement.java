package ru.ludwigandreas.notification.service.inbox;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.ludwigandreas.notification.repository.InboxItemContentRepository;
import ru.ludwigandreas.notification.repository.InboxItemRepository;
import ru.ludwigandreas.notification.repository.entity.InboxItemContentEntity;
import ru.ludwigandreas.notification.repository.entity.InboxItemEntity;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.service.exception.TemplateNotFoundException;
import ru.ludwigandreas.notification.service.exception.TemplateRenderException;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.template.RenderedTemplate;
import ru.ludwigandreas.notification.service.template.TemplateCoordinates;
import ru.ludwigandreas.notification.service.template.TemplateRenderer;

/**
 * Writes one in-app notification into its recipient's inbox, in the transaction that accepted the
 * request.
 *
 * <h2>Why this is not a {@code NotificationChannel}</h2>
 *
 * <p>A transport implementation is contractually forbidden from opening a transaction or touching the
 * database, and the inbox <em>is</em> the database. That contract is not an inconvenience to be
 * worked around here: it exists because a provider call inside a transaction holds a pooled
 * connection for the duration of somebody else's network, which is how one slow SMTP relay drains the
 * pool and takes the ingress down. The work queue in front of it exists for the same reason.
 *
 * <p>Neither hazard exists for this destination. There is no third-party network, no timeout to
 * bound, and no failure that retrying could fix - the "provider" is the same PostgreSQL the fan-out
 * transaction is already writing to. Deferring the insert out of that transaction would turn an exact
 * write into an eventual one, add a retry path whose only possible subject is a local insert, and
 * leave a window in which a notification reported as delivered is not yet readable.
 *
 * <p>So an in-app delivery is <b>settled</b> rather than dispatched: created {@code ACCEPTED} and
 * moved straight to {@code DELIVERED} alongside the {@code SUPPRESSED}, {@code BATCHED} and
 * {@code DEAD} settlements the fan-out already performs, and never written in a state the claim query
 * selects. The boundary of the carve-out is checked rather than described -
 * {@code InAppSettlementIT#noTransportSupportsInApp} fails the build if any implementation claims
 * to support {@code IN_APP}, and {@code InAppSettlementIT#claimIgnoresInAppDeliveries} fails it if
 * one of these rows ever becomes claimable.
 *
 * <h2>Why this renders, when the fan-out deliberately does not</h2>
 *
 * <p>{@code DeliveryFanOutService} states that rendering is deliberately left to send time: it is CPU
 * work whose result is only needed then, doing it at ingress would double that latency, and a
 * template about to be corrected would bake the old wording into every queued row.
 *
 * <p>For a passive channel there is no send time for that argument to attach to, and deferring the
 * render to <em>read</em> time is not merely awkward but impossible. The variable map is scrubbed at
 * {@code retention.recipient-data-ttl}, seven days by default, while an unread item is kept until its
 * owner reads it - so the content of an item still unread in week three could no longer be produced
 * at all. The item therefore carries the wording that was current when it was produced, which is the
 * same promise already made about an email that has been sent.
 *
 * <p>This class accesses neither the delivery content entity nor its repository, and an ArchUnit rule
 * in this service's test sources fails the build if it ever does. That table is purged at
 * {@code retention.content-ttl} with a startup check that a body never outlives its delivery, so a
 * body written there would be deleted out from under an owner who had not yet read it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InAppSettlement {

    private final InboxItemRepository itemRepository;
    private final InboxItemContentRepository contentRepository;
    private final TemplateRenderer templateRenderer;

    /**
     * Renders the notification and stores it as an inbox item.
     *
     * <p>Called with the fan-out's transaction open and deliberately so: the item, the delivery, the
     * request and the idempotency claim commit together or not at all. An item that could commit
     * independently of the request that produced it would be unattributable, and one that committed
     * where the request rolled back would be a notification about something that never happened.
     *
     * <p>No {@code try}/{@code catch} around the write itself. A failure to insert is a failure of the
     * whole fan-out and must roll it back; swallowing it here would report a delivery as
     * {@code DELIVERED} with no inbox item behind it, which is the one outcome worse than a rejected
     * request.
     *
     * @return the stored item, or empty when the template could not be rendered - which the caller
     *         turns into one terminal delivery rather than a failed request, so that the other
     *         recipients and the other channels of the same request are unaffected
     */
    public java.util.Optional<InboxItemEntity> settle(NotificationDeliveryEntity delivery,
                                                      String ownerUserId,
                                                      Map<String, Object> variables,
                                                      Instant now) {
        Locale locale = Locale.forLanguageTag(delivery.getRecipientLocale());
        RenderedTemplate rendered;
        try {
            rendered = templateRenderer.render(
                    new TemplateCoordinates(delivery.getTemplateKey(), ChannelType.IN_APP, locale),
                    variables);
        } catch (TemplateNotFoundException | TemplateRenderException e) {
            // Terminal rather than retryable, and for the same reason the dispatcher treats a render
            // failure as terminal: a template that does not exist or does not compile will not start
            // working on the next attempt. The message is safe to record - a render error names the
            // template and the expression, never the recipient.
            log.warn("No renderable {} template for key {}: {}", ChannelType.IN_APP,
                    delivery.getTemplateKey(), e.getMessage());
            return java.util.Optional.empty();
        }

        // The id is assigned here rather than by the database, and the content row reuses it, which
        // is what makes the one-to-one structural rather than conventional. Set after build because
        // it is declared on JpaBaseEntity, which Lombok's builder does not reach.
        UUID itemId = UUID.randomUUID();
        InboxItemEntity item = InboxItemEntity.builder()
                .ownerUserId(ownerUserId)
                .deliveryId(delivery.getId())
                .requestId(delivery.getRequestId())
                .templateKey(delivery.getTemplateKey())
                .category(delivery.getCategory())
                .categoryClass(delivery.getCategoryKind())
                .priority(delivery.getPriority() == null
                        ? null : (short) delivery.getPriority().getWeight())
                .locale(rendered.resolvedLocale())
                .createdAt(now)
                .build();
        item.setId(itemId);
        item = itemRepository.save(item);

        InboxItemContentEntity content = InboxItemContentEntity.builder()
                .subject(rendered.subject())
                .bodyHtml(rendered.htmlBody())
                .bodyText(rendered.textBody())
                .renderedAt(now)
                .build();
        content.setId(itemId);
        contentRepository.save(content);

        return java.util.Optional.of(item);
    }
}
