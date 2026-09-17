package ru.ludwigandreas.notification.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.GeneratedEntity;

/**
 * One claimed idempotency key, and the request that claimed it.
 *
 * <p>This is the first of the two capabilities the platform does not have yet. The Kafka consumer is
 * at-least-once and REST callers retry, so without a dedup record the same ask sends twice - and at
 * more than one replica it sends twice <em>concurrently</em>, which no amount of read-then-write
 * checking prevents. The unique index on {@code (scope, idempotency_key)} is what actually enforces
 * it: the loser of the race takes a constraint violation and reads back the winner's request id.
 *
 * <p>The window is bounded by {@link #expiresAt}. Keeping keys forever would make the table grow
 * without limit and would forbid a caller from ever reusing a key - both worse than accepting that a
 * redelivery arriving a month later is a new request.
 */
@Entity
@Table(name = "notification_idempotency")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyRecordEntity extends GeneratedEntity<UUID> {

    /**
     * What the key is unique within - the ingress it arrived on.
     *
     * <p>Scoped rather than global because a Kafka message key and an HTTP {@code Idempotency-Key}
     * come from different namespaces, and a collision between them would silently drop a genuine
     * request.
     */
    @Column(name = "scope", nullable = false, updatable = false, length = 64)
    private String scope;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String idempotencyKey;

    /** The request the first caller's ask created; returned verbatim to every later duplicate. */
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
