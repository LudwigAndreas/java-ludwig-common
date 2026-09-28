package ru.ludwigandreas.restclient.config;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;

/**
 * Outbound rate limit for one named client.
 *
 * <p>Per process, not per cluster. The number that matters is therefore the partner's quota divided
 * by the replica count, and it has to be revisited when the deployment is scaled - which the README
 * says out loud, because a limiter that silently stops limiting after an autoscale is worse than no
 * limiter at all.
 */
@Getter
@Setter
public class RateLimiterProperties {

    /** Built-in default: false. */
    private Boolean enabled;

    /** Permits issued per refresh period. Built-in default: 100. */
    @Positive
    private Integer limitForPeriod;

    /** How often the permit count is replenished. Built-in default: 1s. */
    private Duration limitRefreshPeriod;

    /**
     * How long a caller waits for a permit. Built-in default: 0 - reject immediately.
     *
     * <p>A positive value here parks the calling thread, so it must be well below the read timeout;
     * the startup validator enforces that rather than leaving the interaction to be discovered in
     * production.
     */
    private Duration timeoutDuration;
}
