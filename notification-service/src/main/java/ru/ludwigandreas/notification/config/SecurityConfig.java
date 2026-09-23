package ru.ludwigandreas.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.repository.entity.NotificationRequestEntity;
import ru.ludwigandreas.notification.repository.entity.QNotificationDeliveryEntity;
import ru.ludwigandreas.notification.repository.entity.QNotificationRequestEntity;
import ru.ludwigandreas.security.data.DataScopeMapping;

/**
 * The only security code this service writes: which columns carry which scope dimension for the one
 * resource it owns.
 *
 * <p>Everything else - who the caller is, where roles come from, how a 403 is rendered, how a scope
 * becomes a predicate - is autoconfigured by {@code security-spring-boot-starter} and driven by
 * {@code ludwig.security.data.policies} in {@code application.yml}. What cannot be configured is
 * this: the module has no way to know which column of a delivery carries its tenant, so the service
 * states it once, here, in compile-time-checked terms.
 *
 * <p>Renaming either field breaks the build rather than silently un-scoping the delivery history -
 * which is a history of who was told what, and therefore exactly the sort of table that must not
 * quietly become readable across tenants.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** The name the delivery's policies and scope mapping are registered under. */
    public static final String DELIVERY_RESOURCE = "notification-delivery";

    /** The name the request's policies and scope mapping are registered under. */
    public static final String REQUEST_RESOURCE = "notification-request";

    /**
     * Two dimensions, one path and one accessor each.
     *
     * <p>The path is what the module ANDs into every scoped query - the pre-filter that keeps paging
     * and counts correct. The accessor is what it calls to judge a single already-loaded delivery -
     * the post-check that covers {@code findById} and every other path a query predicate never sees.
     * Declared together, they cannot drift apart.
     *
     * <p>{@code tenantId} carries the tenant a delivery belongs to, copied from the requesting
     * principal at fan-out and never from a request body. {@code createdBy} is deliberately absent:
     * a delivery is written by the fan-out rather than by a caller, so the owner dimension would
     * always be this service's own background identity and would scope nothing. The accountable
     * principal is on the request, which has its own mapping below.
     */
    @Bean
    public DataScopeMapping<NotificationDeliveryEntity> deliveryDataScopeMapping() {
        QNotificationDeliveryEntity delivery = QNotificationDeliveryEntity.notificationDeliveryEntity;
        return DataScopeMapping.forResource(DELIVERY_RESOURCE, NotificationDeliveryEntity.class)
                .tenant(delivery.tenantId, NotificationDeliveryEntity::getTenantId)
                .build();
    }

    /**
     * The same two dimensions for the request a caller submitted.
     *
     * <p>Needed because the request became readable: the REST ingress answers 202 with a location,
     * and a location nobody may follow is not an answer. A load by id does not pass through a scoped
     * query, so without this mapping a caller holding an id - its own, from a log, or guessed - could
     * read the template key, the category and the recipient list of any tenant's request.
     *
     * <p>Unlike a delivery, a request <em>does</em> have a meaningful owner: it was submitted by a
     * caller, and {@code createdBy} is that caller. It is mapped so a policy can express "a service
     * may read the requests it submitted and no others", which is the narrowest useful grant for a
     * peer service polling the status of what it sent.
     */
    @Bean
    public DataScopeMapping<NotificationRequestEntity> requestDataScopeMapping() {
        QNotificationRequestEntity request = QNotificationRequestEntity.notificationRequestEntity;
        return DataScopeMapping.forResource(REQUEST_RESOURCE, NotificationRequestEntity.class)
                .tenant(request.tenantId, NotificationRequestEntity::getTenantId)
                .owner(request.createdBy, NotificationRequestEntity::getCreatedBy)
                .build();
    }
}
