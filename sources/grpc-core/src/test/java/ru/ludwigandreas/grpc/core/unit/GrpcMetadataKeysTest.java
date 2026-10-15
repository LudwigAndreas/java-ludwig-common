package ru.ludwigandreas.grpc.core.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.Metadata;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.grpc.core.metadata.GrpcMetadataKeys;

class GrpcMetadataKeysTest {

    @Test
    @DisplayName("every key is a lower-case ASCII name, as HTTP/2 requires")
    void keysAreLowerCase() throws IllegalAccessException {
        List<String> names = new ArrayList<>();
        for (Field field : GrpcMetadataKeys.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Metadata.Key.class) {
                names.add(((Metadata.Key<?>) field.get(null)).name());
            }
        }

        assertThat(names).isNotEmpty().doesNotHaveDuplicates();
        assertThat(names).allSatisfy(name -> assertThat(name).isEqualTo(name.toLowerCase(Locale.ROOT)));
    }

    @Test
    @DisplayName("the keys are the lower-cased HTTP header names their owning modules declare")
    void keysMirrorHeaderNames() {
        assertThat(GrpcMetadataKeys.AUTHORIZATION.name()).isEqualTo("authorization");
        assertThat(GrpcMetadataKeys.CORRELATION_ID.name()).isEqualTo("x-correlation-id");
        assertThat(GrpcMetadataKeys.IDEMPOTENCY_KEY.name()).isEqualTo("idempotency-key");
        assertThat(GrpcMetadataKeys.IDEMPOTENCY_REPLAYED.name()).isEqualTo("idempotency-replayed");
        assertThat(GrpcMetadataKeys.LOCALE.name()).isEqualTo("accept-language");
        assertThat(GrpcMetadataKeys.ZONE.name()).isEqualTo("x-time-zone");
        assertThat(GrpcMetadataKeys.RETRY_AFTER.name()).isEqualTo("retry-after");
    }

    @Test
    @DisplayName("a renamed header yields the matching key")
    void forHeaderLowerCases() {
        assertThat(GrpcMetadataKeys.forHeader(" X-Request-Id ").name()).isEqualTo("x-request-id");
        assertThat(GrpcMetadataKeys.forHeader("X-Correlation-Id")).isEqualTo(GrpcMetadataKeys.CORRELATION_ID);
    }

    @Test
    @DisplayName("a blank header name is refused rather than turned into an empty key")
    void forHeaderRejectsBlank() {
        assertThatThrownBy(() -> GrpcMetadataKeys.forHeader(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrpcMetadataKeys.forHeader(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
