package ru.ludwigandreas.grpc.server.properties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * {@code ludwig.grpc.server.*}.
 *
 * <p>Every default here is a decision, and each is one a hand-built server leaves to chance: an
 * unbounded message, an unbounded executor queue, a connection that never rebalances. The README's
 * configuration table gives the reason beside each key.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = GrpcServerProperties.PREFIX)
public class GrpcServerProperties {

    /** The property prefix. */
    public static final String PREFIX = "ludwig.grpc.server";

    private static final int DEFAULT_PORT = 9090;
    private static final int MAX_PORT = 65_535;
    private static final int DEFAULT_MESSAGE_MEGABYTES = 4;
    private static final int DEFAULT_METADATA_KILOBYTES = 8;
    private static final int DEFAULT_CONNECTION_AGE_MINUTES = 30;
    private static final int DEFAULT_GRACE_SECONDS = 30;
    private static final int DEFAULT_CALLS_PER_CONNECTION = 100;
    private static final int DEFAULT_HANDSHAKE_SECONDS = 20;

    /** {@code false} binds nothing, whatever services are declared. */
    private boolean enabled = true;

    /** The port to listen on. {@code 0} picks a free one, which is what a test wants. */
    @Min(0)
    @Max(MAX_PORT)
    private int port = DEFAULT_PORT;

    /** The address to bind. Empty binds every interface. */
    private String address;

    /**
     * Serve on the in-process transport under this name instead of on a port. For tests: no socket,
     * no TLS, and the same interceptor chain.
     */
    private String inProcessName;

    /** The largest request message accepted. A larger one is answered {@code RESOURCE_EXHAUSTED}. */
    private DataSize maxInboundMessageSize = DataSize.ofMegabytes(DEFAULT_MESSAGE_MEGABYTES);

    /** The largest request metadata accepted. A JWT fits; a header bomb does not. */
    private DataSize maxInboundMetadataSize = DataSize.ofKilobytes(DEFAULT_METADATA_KILOBYTES);

    /**
     * How long a connection lives before the server asks the client to reconnect. This is what
     * makes callers re-resolve, and therefore what sends traffic to a pod added by a scale-out.
     */
    private Duration maxConnectionAge = Duration.ofMinutes(DEFAULT_CONNECTION_AGE_MINUTES);

    /** How long calls in flight get to finish once a connection has reached its maximum age. */
    private Duration maxConnectionAgeGrace = Duration.ofSeconds(DEFAULT_GRACE_SECONDS);

    /** Concurrent calls one connection may hold, so one caller cannot monopolise a pod. */
    @Min(1)
    private int maxConcurrentCallsPerConnection = DEFAULT_CALLS_PER_CONNECTION;

    /** How long a new connection gets to finish its TLS and HTTP/2 handshake. */
    private Duration handshakeTimeout = Duration.ofSeconds(DEFAULT_HANDSHAKE_SECONDS);

    /** How long calls in flight get to finish on shutdown before they are cancelled. */
    private Duration shutdownGrace = Duration.ofSeconds(DEFAULT_GRACE_SECONDS);

    /**
     * Serve without TLS on a non-loopback address. Takes effect only together with the top-level
     * {@code ludwig.grpc.allow-plaintext}, and never under a production profile.
     */
    private boolean plaintext;

    private final Keepalive keepalive = new Keepalive();

    @Valid
    private final Executor executor = new Executor();

    private final Tls tls = new Tls();

    private final Security security = new Security();

    private final Reflection reflection = new Reflection();

    /** What the server tolerates from a client's keepalive pings. */
    @Getter
    @Setter
    public static class Keepalive {

        private static final int DEFAULT_PERMIT_SECONDS = 30;

        /**
         * The shortest interval between pings a client may use. Must not exceed the clients'
         * keepalive time, or the server answers their pings with {@code GOAWAY}; the client
         * starter's default is aligned with this one, and the two move together.
         */
        private Duration permitTime = Duration.ofSeconds(DEFAULT_PERMIT_SECONDS);

        /** Whether a client may ping a connection that has no call in flight. */
        private boolean permitWithoutCalls = true;
    }

    /** The pool handlers run on. */
    @Getter
    @Setter
    public static class Executor {

        private static final int DEFAULT_THREADS = 64;
        private static final int DEFAULT_QUEUE = 256;

        /** Handler threads. Handlers block - on a database, on another service - so this is a cap on that. */
        @Min(1)
    private int threads = DEFAULT_THREADS;

        /**
         * Calls that may wait for a thread. Beyond {@code threads + queue-capacity} a new call is
         * answered {@code RESOURCE_EXHAUSTED} instead of being queued without bound.
         */
        @Min(0)
    private int queueCapacity = DEFAULT_QUEUE;
    }

    /** Transport security. */
    @Getter
    @Setter
    public static class Tls {

        /**
         * The name of a Spring Boot SSL bundle ({@code spring.ssl.bundle.*}) holding the server's
         * key and certificate, and its trust material when clients present certificates.
         */
        private String bundle;

        /** Whether clients must, may or need not present a certificate. */
        private ClientAuth clientAuth = ClientAuth.NONE;
    }

    /** Whether a client certificate is asked for. */
    public enum ClientAuth {
        /** Not requested. */
        NONE,
        /** Requested; a client without one is still served. */
        OPTIONAL,
        /** Required; the handshake fails without one. */
        REQUIRE
    }

    /** Authentication of callers. */
    @Getter
    @Setter
    public static class Security {

        /**
         * {@code false} serves every method unauthenticated. It has to be written down: with this
         * left at {@code true} and no way to authenticate, the application refuses to start.
         */
        private boolean enabled = true;

        /**
         * Methods served without a credential, as {@code package.Service/Method} or
         * {@code package.Service/*}. Health and an enabled reflection service are always permitted.
         */
        private List<String> permitUnauthenticated = new ArrayList<>();
    }

    /** Server reflection. */
    @Getter
    @Setter
    public static class Reflection {

        /** Off by default, and refused outright under a production profile. */
        private boolean enabled;
    }
}
