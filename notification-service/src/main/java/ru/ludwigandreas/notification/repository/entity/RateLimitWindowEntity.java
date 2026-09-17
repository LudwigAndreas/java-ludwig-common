package ru.ludwigandreas.notification.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Permits consumed by one channel in one fixed time window, cluster-wide.
 *
 * <p>An in-process token bucket is the obvious implementation and it is wrong here. Three replicas
 * each holding a 100-per-minute bucket send 300 per minute, so the number in the configuration file
 * would mean nothing and would quietly change meaning every time the deployment scaled. A provider
 * that rate-limits us does not care how many pods we run.
 *
 * <p>So the counter is a row, and permits are <em>reserved before the claim</em>, in batch: the
 * poller asks for up to a batch's worth, is told how many it may have, and claims at most that many
 * deliveries. That costs one extra statement per channel per poll cycle - not per message - and it
 * makes the configured rate the actual rate at any replica count.
 */
@Entity
@Table(name = "notification_rate_limit_window")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RateLimitWindowEntity extends GeneratedEntity<UUID> {

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 16)
    private ChannelKind channel;

    /** Start of the window, truncated to the configured window length. Unique with the channel. */
    @Column(name = "window_start", nullable = false, updatable = false)
    private Instant windowStart;

    @Column(name = "permits_used", nullable = false)
    private int permitsUsed;
}
