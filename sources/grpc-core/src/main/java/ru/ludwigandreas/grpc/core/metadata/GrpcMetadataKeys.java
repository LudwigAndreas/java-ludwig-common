package ru.ludwigandreas.grpc.core.metadata;

import io.grpc.Metadata;
import java.util.Locale;

/**
 * The metadata keys two platform services exchange, declared once.
 *
 * <h2>Why each name is an HTTP header name in lower case</h2>
 *
 * <p>Every value here already travels between services as an HTTP header, under a name its owning
 * module declares. The gRPC key is that same name lower-cased (HTTP/2 requires lower case), so that
 * there is one name per concept across both transports: a gateway that transcodes needs no mapping
 * table, and an engineer reading a capture does not have to know which transport it came from.
 *
 * <p>The owning modules are not dependencies of this one - {@code grpc-core} depends on
 * {@code web-core} alone - so the literals are repeated here and each constant names the
 * declaration it mirrors. The starters that do depend on both sides carry a test asserting the two
 * are equal; that test, not this comment, is what stops them drifting.
 */
public final class GrpcMetadataKeys {

    /**
     * The caller's credential. Mirrors HTTP {@code Authorization}.
     */
    public static final Metadata.Key<String> AUTHORIZATION = key("authorization");

    /**
     * The correlation id, under its default name. Mirrors
     * {@code ludwig.observability.correlation.header-name}, whose default is
     * {@code X-Correlation-Id}; a deployment that renames the header gets the matching key from
     * {@link #forHeader(String)}.
     */
    public static final Metadata.Key<String> CORRELATION_ID = key("x-correlation-id");

    /**
     * The idempotency key. Mirrors {@code IdempotencyHeaders.KEY} in
     * {@code idempotency-spring-boot-starter}.
     */
    public static final Metadata.Key<String> IDEMPOTENCY_KEY = key("idempotency-key");

    /**
     * Set to {@code true} on a reply that was replayed rather than produced. Mirrors
     * {@code IdempotencyHeaders.REPLAYED}.
     */
    public static final Metadata.Key<String> IDEMPOTENCY_REPLAYED = key("idempotency-replayed");

    /**
     * The caller's locale, as a language range. Mirrors HTTP {@code Accept-Language}, which
     * web-core's {@code RequestHeaderPreferenceSource} reads.
     */
    public static final Metadata.Key<String> LOCALE = key("accept-language");

    /**
     * The caller's time zone, as an IANA id. Mirrors {@code ludwig.web.preference}'s time-zone
     * header, whose default is {@code X-Time-Zone}.
     */
    public static final Metadata.Key<String> ZONE = key("x-time-zone");

    /**
     * Seconds to wait before repeating a call. Mirrors {@code OperationHeaders.RETRY_AFTER}.
     */
    public static final Metadata.Key<String> RETRY_AFTER = key("retry-after");

    private GrpcMetadataKeys() {
    }

    /**
     * The metadata key for an HTTP header name.
     *
     * @param headerName a header name in any case, for example {@code X-Correlation-Id}
     * @return the ASCII metadata key under the lower-cased name
     */
    public static Metadata.Key<String> forHeader(String headerName) {
        if (headerName == null || headerName.isBlank()) {
            throw new IllegalArgumentException("A metadata key needs a header name");
        }
        return key(headerName.trim().toLowerCase(Locale.ROOT));
    }

    private static Metadata.Key<String> key(String name) {
        return Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER);
    }
}
