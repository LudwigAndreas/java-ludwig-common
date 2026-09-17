package ru.ludwigandreas.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.notification.repository.entity.NotificationDeliveryEntity;
import ru.ludwigandreas.notification.repository.entity.QNotificationDeliveryEntity;
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
     * principal is on the request, and the request is not directly readable.
     */
    @Bean
    public DataScopeMapping<NotificationDeliveryEntity> deliveryDataScopeMapping() {
        QNotificationDeliveryEntity delivery = QNotificationDeliveryEntity.notificationDeliveryEntity;
        return DataScopeMapping.forResource(DELIVERY_RESOURCE, NotificationDeliveryEntity.class)
                .tenant(delivery.tenantId, NotificationDeliveryEntity::getTenantId)
                .build();
    }
}
