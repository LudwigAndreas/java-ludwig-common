package ru.ludwigandreas.messaging.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import ru.ludwigandreas.idempotency.api.ClaimMode;
import ru.ludwigandreas.idempotency.config.IdempotencyProperties;
import ru.ludwigandreas.messaging.settings.DedupTransactionPolicy;
import ru.ludwigandreas.messaging.settings.MessagingProperties;

/**
 * Decides whether a dedup container needs a transaction manager, by reading the claim mode.
 *
 * <p>This is the whole extent of this module's involvement with dedup, and it is deliberately small. The
 * inbox already exists: {@code idempotency-spring-boot-starter} ships
 * {@code IdempotentRecordFilterStrategy}, the claim table, the scopes, the TTL and the retention purge. A
 * second dedup store here would recreate, in one commit, precisely the two-homes problem this series of
 * consolidations has been removing. What was missing was never a store - it was that adopting the store
 * took a line in every service's own container factory, so a service got dedup by remembering rather than
 * by configuration. {@code ListenerContainerFactoryBuilder} attaches the filter by default; this class
 * answers the one question the builder cannot.
 *
 * <p>The question is whether the claim commits with the listener's work. A
 * {@link ClaimMode#TRANSACTIONAL} claim on a container with no transaction manager commits outside the
 * listener's transaction, so the key stays reserved for work that rolled back - and Kafka's redelivery of
 * that record, certain because the offset was not committed either, is discarded as a duplicate. The
 * message is never delivered and nothing says so. That is a data-loss path arrived at by omission, which
 * is why it is decided here rather than documented.
 *
 * <p>Conditional on {@code IdempotencyProperties} being loadable, and registered before
 * {@code MessagingAutoConfiguration}, whose fallback policy reads only the explicit property.
 */
@AutoConfiguration(before = MessagingAutoConfiguration.class)
@ConditionalOnClass({KafkaListener.class, IdempotencyProperties.class})
@ConditionalOnProperty(prefix = MessagingProperties.PREFIX, name = {"enabled", "dedup.enabled"},
        matchIfMissing = true)
@EnableConfigurationProperties({MessagingProperties.class, IdempotencyProperties.class})
public class MessagingDedupAutoConfiguration {

    /**
     * The policy, preferring an explicit setting and otherwise following the claim mode.
     *
     * @param messaging    this module's configuration
     * @param idempotency  the idempotency module's configuration, read for its Kafka claim mode
     * @return the policy
     */
    @Bean
    @ConditionalOnMissingBean(DedupTransactionPolicy.class)
    public DedupTransactionPolicy ludwigDedupTransactionPolicy(MessagingProperties messaging,
                                                               IdempotencyProperties idempotency) {
        Boolean explicit = messaging.getDedup().getContainerTransaction();
        ClaimMode mode = idempotency.getKafka().getMode();
        if (Boolean.FALSE.equals(explicit) && mode == ClaimMode.TRANSACTIONAL) {
            // Refused rather than obeyed. The combination is not a trade-off a deployment can make: the
            // claim would commit outside the listener's transaction, so a rollback leaves the key held and
            // the certain redelivery is discarded as a duplicate. The message is lost and nothing reports
            // it. Either mode is STANDALONE, which reclaims a stuck key under a lease, or the container
            // gets a transaction manager.
            throw new IllegalStateException(MessagingProperties.PREFIX
                    + ".dedup.container-transaction=false with ludwig.idempotency.kafka.mode=TRANSACTIONAL"
                    + " would commit a claim outside the listener's transaction, so a key stays reserved"
                    + " for work that rolled back and its redelivery is discarded as a duplicate - the"
                    + " message would be lost silently. Use ClaimMode.STANDALONE, or let the container"
                    + " have the transaction manager.");
        }
        if (explicit != null) {
            return new DedupTransactionPolicy(explicit,
                    MessagingProperties.PREFIX + ".dedup.container-transaction=" + explicit);
        }
        return new DedupTransactionPolicy(mode == ClaimMode.TRANSACTIONAL,
                "ludwig.idempotency.kafka.mode=" + mode);
    }
}
