package ru.ludwigandreas.pat.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The issuer's configuration: what it will mint, for how long, and what it refuses.
 *
 * <p>Several defaults here are deliberately strict, and each strict default is one an operator can relax
 * with a line of YAML an auditor can read. That is the shape worth having: a deployment that wants
 * hundred-year tokens may have them, and the decision is visible in configuration rather than implied by the
 * absence of a check.
 *
 * <p>{@link Validated}, and the constraints are the point of it: a bad value here fails the <b>pod</b> rather
 * than the first request that happens to depend on it. A null {@code maxLifetime} would make every issuance
 * throw at the comparison, which presents as an outage with a stack trace rather than as a configuration
 * error with a property name - and the two lead to very different first hours.
 */
@ConfigurationProperties(prefix = "ludwig.pat")
@Validated
@Getter
@Setter
public class PatProperties {

    /** Master switch for this module's autoconfiguration. */
    private boolean enabled = true;

    /**
     * The longest lifetime this issuer will mint.
     *
     * <p>Ninety days. Refused rather than clamped when a request exceeds it: a caller who believes they hold
     * a one-year token and actually hold a ninety-day one finds out when their pipeline breaks, at which
     * point the token is gone and the failure looks like an outage.
     */
    @NotNull
    private Duration maxLifetime = Duration.ofDays(90);

    /**
     * Whether a token may be issued with no expiry at all.
     *
     * <p>Off. A credential with no expiry outlives every process that knows it exists - the pipeline that
     * used it, the person who minted it, and the ticket that explains why. Turning this on is a deliberate
     * act, which is the point.
     */
    private boolean allowNonExpiring = false;

    /**
     * How long a superseded secret keeps working after a rotation.
     *
     * <p>Twenty-four hours. A rotation that invalidated the old secret immediately would require every
     * consumer to be updated atomically; nothing real can do that, so the observed outcome is that nobody
     * rotates. Bounded by {@link #maxLifetime} like everything else.
     */
    @NotNull
    private Duration rotationOverlap = Duration.ofHours(24);

    /**
     * How long a terminal record is kept before purging, with its digests already destroyed.
     *
     * <p>A year, because the question "what could that credential do?" is asked during an investigation that
     * may start long after the token died.
     */
    @NotNull
    private Duration retention = Duration.ofDays(365);

    /**
     * The minimum interval between last-use writes for one token.
     *
     * <p>Five minutes. Per-request writes to one hot row are write amplification on the exchange path and a
     * contention point under load; what an operator needs from this field is "roughly when", not "exactly
     * when".
     */
    @NotNull
    private Duration lastUsedDebounce = Duration.ofMinutes(5);

    /** How long a token may go unused before it is revoked. Zero disables inactivity expiry. */
    private Duration inactivityExpiry = Duration.ofDays(180);

    /**
     * How quiet a token must have been for its next use to be a dormant-wake signal.
     *
     * <p>Seven days. Deliberately a different and much shorter number than {@link #inactivityExpiry}:
     * that one decides when a quiet token is <b>revoked</b>, this one decides when its reappearance is
     * worth <b>telling somebody about</b>. A credential that goes quiet for a fortnight and then starts
     * being used is the shape of one found in an old repository, and waiting a hundred and eighty days to
     * mention it would be waiting until it had been revoked anyway.
     *
     * <p>Zero disables the signal without disabling inactivity expiry, which is the combination a
     * deployment with very bursty automation wants.
     */
    private Duration dormancyThreshold = Duration.ofDays(7);

    /** Rows handled per sweep, so a backlog cannot turn one scheduled run into an unbounded transaction. */
    @Min(1)
    private int sweepBatchSize = 500;

    @Valid
    private final Exchange exchange = new Exchange();

    @Valid
    private final RateLimit rateLimit = new RateLimit();

    /** The RFC 8693 exchange endpoint and the assertion it mints. */
    @Getter
    @Setter
    public static class Exchange {

        private boolean enabled = true;

        /** Where the endpoint is mounted. */
        @NotBlank
        private String path = "/oauth2/token";

        /**
         * The {@code iss} of the minted assertion. Startup fails if the exchange is on and this is blank.
         */
        private String issuer;

        /**
         * The audiences this issuer will mint for.
         *
         * <p>The exchange refuses a requested audience that is not in this list <b>and</b> not in the
         * token's own audience set. Two gates rather than one: this one is what the deployment will mint at
         * all, the token's is what that particular token may reach.
         */
        private List<String> audiences = new ArrayList<>();

        /**
         * How long a minted assertion is valid.
         *
         * <p>Five minutes, and it is also the lifetime the response declares for the edge to cache by - so
         * shortening it shortens the revocation window and lengthens the issuer's load in the same move.
         * {@code ludwig.security.pat.assertion-lifetime} must agree with this; the security module's startup
         * check computes the revocation window from its own copy and cannot read this one.
         */
        @NotNull
        private Duration assertionLifetime = Duration.ofMinutes(5);
    }

    /** Limits on the one endpoint in the platform where a credential guess can be tested. */
    @Getter
    @Setter
    public static class RateLimit {

        private boolean enabled = true;

        /** Failed attempts allowed per key id per window, before that key id is refused outright. */
        @Min(1)
        private int perKeyFailures = 10;

        /**
         * Failed attempts allowed per source address per window.
         *
         * <p>Separate from the per-key limit because a per-key limit alone does not bound an attacker
         * enumerating key ids - each guess is a different key, so each gets its own fresh budget.
         */
        @Min(1)
        private int perSourceFailures = 100;

        @NotNull
        private Duration window = Duration.ofMinutes(1);
    }
}
