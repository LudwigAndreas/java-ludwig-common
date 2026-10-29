package ru.ludwigandreas.grpc.server.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.grpc.core.metadata.GrpcMetadataKeys;
import ru.ludwigandreas.idempotency.api.IdempotencyHeaders;
import ru.ludwigandreas.observability.config.ObservabilityProperties;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.webcore.config.WebCoreProperties;
import ru.ludwigandreas.webcore.operation.OperationHeaders;

/**
 * Each metadata key is its owning module's header name, lower-cased. {@code grpc-core} repeats the
 * literals because it depends on none of those modules; this starter depends on all of them, which
 * makes it the place the repetition is checked.
 */
class GrpcMetadataKeysParityTest {

    private static String lower(String header) {
        return header.toLowerCase(Locale.ROOT);
    }

    @Test
    @DisplayName("idempotency key and replayed marker match IdempotencyHeaders")
    void idempotency() {
        assertThat(GrpcMetadataKeys.IDEMPOTENCY_KEY.name()).isEqualTo(lower(IdempotencyHeaders.KEY));
        assertThat(GrpcMetadataKeys.IDEMPOTENCY_REPLAYED.name()).isEqualTo(lower(IdempotencyHeaders.REPLAYED));
    }

    @Test
    @DisplayName("the correlation key matches observability's default header name")
    void correlation() {
        assertThat(GrpcMetadataKeys.CORRELATION_ID.name())
                .isEqualTo(lower(new ObservabilityProperties().getCorrelation().getHeaderName()));
    }

    @Test
    @DisplayName("the zone key matches web-core's default time-zone header")
    void zone() {
        assertThat(GrpcMetadataKeys.ZONE.name())
                .isEqualTo(lower(new WebCoreProperties().getPreferences().getTimeZoneHeader()));
    }

    @Test
    @DisplayName("the admin reason key matches AdminInvocation's header")
    void adminReason() {
        assertThat(GrpcMetadataKeys.ADMIN_REASON.name()).isEqualTo(lower(AdminInvocation.REASON_HEADER));
    }

    @Test
    @DisplayName("retry-after matches OperationHeaders")
    void retryAfter() {
        assertThat(GrpcMetadataKeys.RETRY_AFTER.name()).isEqualTo(lower(OperationHeaders.RETRY_AFTER));
    }
}
