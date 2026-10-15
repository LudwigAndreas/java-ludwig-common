package ru.ludwigandreas.grpc.client.properties;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.unit.DataSize;

/**
 * One client's settings, or the defaults block.
 *
 * <p>Every field is nullable and every nested block starts {@code null}: "not written" has to be
 * distinguishable from "written as the default", or a client could not inherit a value its
 * defaults block sets. The built-in values live in {@link GrpcClientPropertiesMerger#builtIn()}.
 * Every nested type is declared in this package, which is how the merger tells a block it merges
 * field by field from a value it replaces.
 */
@Getter
@Setter
public class ClientSettings {

    /**
     * Where to connect: {@code dns:///host:port} (balanced per call over every address),
     * {@code host:port}, or {@code in-process:<name>} in a test. Never inherited.
     */
    private String target;

    /** The ceiling on every call, retries included. Refreshable. Built-in: 5s. */
    private Duration deadline;

    /** {@code round_robin} (built-in) or {@code pick_first}, which is logged at WARN. */
    private String loadBalancing;

    /** Connect without TLS. Effective only with {@code ludwig.grpc.allow-plaintext}, or to a loopback target. */
    private Boolean plaintext;

    /** The largest reply accepted. Built-in: 4MB. */
    private DataSize maxInboundMessageSize;

    /** Close the connection after this long without a call. Built-in: 30m. */
    private Duration idleTimeout;

    private Keepalive keepalive;

    private Tls tls;

    private Resilience resilience;

    private Retry retry;

    private Auth auth;

    private Logging logging;

    private Audit audit;

    /** Pings on an idle connection. */
    @Getter
    @Setter
    public static class Keepalive {

        /** Built-in 30s: not shorter than the server starter's {@code keepalive.permit-time}. Move them together. */
        private Duration time;

        /** Built-in 20s. */
        private Duration timeout;

        /** Built-in false. */
        private Boolean withoutCalls;
    }

    /** Transport security. */
    @Getter
    @Setter
    public static class Tls {

        /**
         * A Spring Boot SSL bundle holding the trust material, and a key for mTLS. Without one the JDK's trust store is
         * used.
         */
        private String bundle;
    }

    /** The three Resilience4j policies, outermost first. */
    @Getter
    @Setter
    public static class Resilience {

        private RateLimiter rateLimiter;

        private Bulkhead bulkhead;

        private CircuitBreaker circuitBreaker;
    }

    /** Declaring this block enables it. Limits are refreshable. */
    @Getter
    @Setter
    public static class RateLimiter {

        /** Calls per refresh period. Built-in 100. */
        private Integer limitForPeriod;

        /** Built-in 1s. */
        private Duration refreshPeriod;

        /** How long a call waits for a permit before it is refused. Built-in 0. */
        private Duration timeout;
    }

    /** Declaring this block enables it. */
    @Getter
    @Setter
    public static class Bulkhead {

        /** Calls in flight at once. Built-in 25. */
        private Integer maxConcurrentCalls;

        /** How long a call waits for a slot before it is refused. Built-in 0. */
        private Duration maxWait;
    }

    /** On by default. */
    @Getter
    @Setter
    public static class CircuitBreaker {

        /** Built-in true. */
        private Boolean enabled;

        /** Percent of counted failures that opens the breaker. Built-in 50. */
        private Float failureRateThreshold;

        /** Logical calls in the window. Built-in 100. */
        private Integer slidingWindowSize;

        /** Calls before a rate is computed; must not exceed the window or the breaker can never open. Built-in 20. */
        private Integer minimumNumberOfCalls;

        /** Built-in 30s. */
        private Duration waitDurationInOpenState;
    }

    /**
     * gRPC's own retry, applied only to methods whose contract declares {@code IDEMPOTENT} or
     * {@code NO_SIDE_EFFECTS}. There is deliberately no way to opt a method in from here.
     */
    @Getter
    @Setter
    public static class Retry {

        /** Built-in true. */
        private Boolean enabled;

        /** Attempts in total, the first included. Built-in 3. */
        private Integer maxAttempts;

        /** Built-in 100ms. */
        private Duration initialBackoff;

        /** Built-in 2s. */
        private Duration maxBackoff;

        /** Built-in 2. */
        private Double backoffMultiplier;

        /** Codes retried, besides none. Built-in [UNAVAILABLE]. A list: a client's replaces the defaults'. */
        private List<String> retryOn;

        /** Built-in 10: retries stop when this many recent calls have failed. */
        private Integer throttlingMaxTokens;

        /** Built-in 0.1. */
        private Double throttlingTokenRatio;
    }

    /** How the client authenticates. */
    @Getter
    @Setter
    public static class Auth {

        /**
         * {@code none} (built-in), {@code bearer}, {@code oauth2-client-credentials}, {@code oauth2-token-relay}, or a
         * provider's type.
         */
        private String type;

        /** The token, for {@code bearer}. */
        private String token;

        /** A {@code spring.security.oauth2.client.registration} id, for {@code oauth2-client-credentials}. */
        private String registrationId;

        /** Narrows the registration's scopes. */
        private List<String> scopes;

        /** How early a machine token is renewed. Built-in 30s. */
        private Duration refreshSkew;

        /** Must be set on the client itself for {@code oauth2-token-relay}. Never inherited. */
        private Boolean relayEnabled;

        /** Free-form settings for a provider-defined type. */
        private Map<String, String> properties;
    }

    /** The per-client log line. Refreshable. */
    @Getter
    @Setter
    public static class Logging {

        /** {@code none}, {@code basic} (built-in) or {@code payload}, which is refused in production. */
        private String level;

        /** Characters of a payload logged at most. Built-in 2048. */
        private Integer maxPayloadChars;
    }

    /** Audit records into the platform's sink. */
    @Getter
    @Setter
    public static class Audit {

        /** Built-in false. */
        private Boolean enabled;

        /** Share of successful calls recorded; failures and refusals always are. Refreshable. Built-in 1.0. */
        private Double successSampling;
    }
}
