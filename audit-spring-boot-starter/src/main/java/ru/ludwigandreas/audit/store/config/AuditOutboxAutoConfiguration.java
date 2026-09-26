package ru.ludwigandreas.audit.store.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.config.AuditProperties;
import ru.ludwigandreas.audit.store.sink.CategoryFilteringAuditSink;
import ru.ludwigandreas.audit.store.sink.OutboxAuditSink;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;

/**
 * Ships the trail off-platform, when a deployment asks for it.
 *
 * <p>Off by default. Publishing a service's whole audit trail onto a broker is a data decision, and a
 * starter that made it by being added to a classpath would be making it on the deployment's behalf.
 */
@AutoConfiguration
@ConditionalOnClass(OutboxEventPublisher.class)
@ConditionalOnBean(OutboxEventPublisher.class)
@ConditionalOnProperty(prefix = "ludwig.audit.outbox", name = "enabled", havingValue = "true")
@AutoConfigureAfter(AuditPersistenceAutoConfiguration.class)
@EnableConfigurationProperties(AuditProperties.class)
public class AuditOutboxAutoConfiguration {

    /**
     * The outbox sink, restricted to the categories a deployment named.
     *
     * <p>Wrapped in a {@link CategoryFilteringAuditSink} and not exposed bare, because
     * {@code OutboxEventPublisher} is {@code Propagation.MANDATORY}: an event emitted outside a
     * transaction - an authorization denial, an outbound call record - would become an
     * {@code IllegalTransactionStateException} on the business path rather than an audit row. The filter
     * is what makes "ship the trail to the SIEM" configurable without that being a footgun.
     *
     * <p>Ordered last in the composite, after the log and the table: the broker is the slowest and the
     * most remote of the three, and an event that reached the table and not the broker is recoverable
     * while the reverse is not.
     *
     * @param outbox     the transactional outbox
     * @param properties names the categories shipped
     * @return the sink
     */
    @Bean
    @Order(200)
    @ConditionalOnMissingBean(name = "outboxAuditSink")
    public AuditSink outboxAuditSink(OutboxEventPublisher outbox, AuditProperties properties) {
        return new CategoryFilteringAuditSink(new OutboxAuditSink(outbox),
                properties.getOutbox().getCategories());
    }
}
