package ru.ludwigandreas.notification.service.mapper;

import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import ru.ludwigandreas.notification.repository.entity.DeliveryContentEntity;
import ru.ludwigandreas.notification.repository.entity.DeliveryStatusHistoryEntity;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.repository.entity.NotificationRequestEntity;
import ru.ludwigandreas.notification.service.Pii;
import ru.ludwigandreas.notification.service.model.DeliveryContentView;
import ru.ludwigandreas.notification.service.model.DeliveryTransition;
import ru.ludwigandreas.notification.service.model.DeliveryView;
import ru.ludwigandreas.notification.service.model.NotificationRequestView;

/**
 * Boundary between the persistence model and the service model, generated at compile time by
 * MapStruct - no hand-written field-by-field copying, and a compile error the moment the two models
 * drift apart, because the build sets {@code unmappedTargetPolicy=ERROR}.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>Constructing a {@code NotificationDeliveryEntity} is not a mapping and is not done by MapStruct.
 * A delivery is built from five inputs - the request, the resolved recipient, the channel, the
 * preference decision and the retry configuration - and more than half its columns are derived rather
 * than copied: the dedup key is composed, {@code nextAttemptAt} is the later of a schedule and now,
 * {@code maxAttempts} is a configuration snapshot, the status is the outcome of the preference
 * evaluation. Expressed through MapStruct that would be a dozen {@code expression = "java(...)"}
 * attributes, which is hand-written code with worse locality and no compile-time checking of what is
 * inside the strings. It lives in {@code DeliveryFanOutService} as an ordinary Lombok builder call,
 * where it reads as the construction it is.
 *
 * <p>The direction that <em>is</em> a copy - entity to view - is exactly what MapStruct is for, and
 * it is the direction that matters most: it is where a new column silently failing to reach the API
 * would go unnoticed, and where the enum twins are proved to line up.
 */
@Mapper
public interface NotificationEntityMapper {

    /**
     * The recipient reference is composed rather than copied, because neither of the two columns it
     * is built from may be returned as-is: the user id is only exposed to admins, and the address is
     * never exposed at all.
     */
    @Mapping(target = "recipientReference", expression = "java(reference(entity))")
    @Mapping(target = "lastFailureClass", source = "lastFailureKind")
    @Mapping(target = "state", source = "status")
    @Mapping(target = "categoryClass", source = "categoryKind")
    DeliveryView toView(NotificationDeliveryEntity entity);

    @Mapping(target = "deliveryId", source = "entity.id")
    @Mapping(target = "templateVersion", source = "templateVersion")
    @Mapping(target = "htmlBody", source = "entity.bodyHtml")
    @Mapping(target = "textBody", source = "entity.bodyText")
    DeliveryContentView toView(DeliveryContentEntity entity, String templateVersion);

    @Mapping(target = "state", source = "entity.status")
    @Mapping(target = "categoryClass", source = "entity.categoryKind")
    @Mapping(target = "deliveries", source = "deliveries")
    @Mapping(target = "duplicate", source = "duplicate")
    NotificationRequestView toView(NotificationRequestEntity entity, List<DeliveryView> deliveries,
                                   boolean duplicate);

    List<DeliveryView> toViews(List<NotificationDeliveryEntity> entities);

    @Mapping(target = "from", source = "fromStatus")
    @Mapping(target = "to", source = "toStatus")
    DeliveryTransition toTransition(DeliveryStatusHistoryEntity entity);

    List<DeliveryTransition> toTransitions(List<DeliveryStatusHistoryEntity> entities);

    /**
     * A stable, non-identifying label for one delivery of a multi-recipient request.
     *
     * <p>A user id is a pseudonymous subject and is returned in full - an operator needs to be able
     * to correlate a delivery with a support ticket. An address is personal data and is masked, so
     * the reference is enough to tell two deliveries apart and not enough to harvest.
     */
    default String reference(NotificationDeliveryEntity entity) {
        if (entity == null) {
            return null;
        }
        return entity.getRecipientUserId() != null
                ? "user:" + entity.getRecipientUserId()
                : "address:" + Pii.address(entity.getRecipientAddress());
    }
}
