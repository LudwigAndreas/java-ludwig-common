package ru.ludwigandreas.idempotency.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.idempotency.api.RequestFingerprint;

/**
 * The fingerprint's job is to say "same request" and "different request", and both halves have a failure
 * mode that matters.
 *
 * <p>Saying "different" about a retry refuses a legitimate request with a 422 and turns this feature from
 * a safety net into an outage. Saying "same" about a genuinely different request answers a caller with
 * somebody else's resource, which is the data-integrity failure the fingerprint exists to prevent. The
 * tests below are one per way each of those could happen.
 */
class RequestFingerprintTest {

    private final RequestFingerprint fingerprints = new RequestFingerprint(new ObjectMapper());

    @Test
    @DisplayName("a re-serialised body with reordered keys is the same request")
    void jsonKeyOrderDoesNotMatter() {
        String first = fingerprints.of("POST", "/orders", "application/json",
                bytes("{\"amount\":10,\"currency\":\"EUR\"}"));
        String second = fingerprints.of("POST", "/orders", "application/json",
                bytes("{\"currency\":\"EUR\",\"amount\":10}"));

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("whitespace and nested key order do not matter either")
    void whitespaceAndNestingDoNotMatter() {
        String compact = fingerprints.of("POST", "/orders", "application/json",
                bytes("{\"a\":{\"x\":1,\"y\":2},\"b\":[1,2]}"));
        String pretty = fingerprints.of("POST", "/orders", "application/json",
                bytes("{\n  \"b\" : [ 1, 2 ],\n  \"a\" : { \"y\" : 2, \"x\" : 1 }\n}"));

        assertThat(pretty).isEqualTo(compact);
    }

    @Test
    @DisplayName("array order does matter, because a JSON array is ordered by specification")
    void arrayOrderMatters() {
        String first = fingerprints.of("POST", "/orders", "application/json", bytes("{\"ids\":[1,2]}"));
        String second = fingerprints.of("POST", "/orders", "application/json", bytes("{\"ids\":[2,1]}"));

        // Two requests whose arrays differ only in order are two different asks, and this module is in no
        // position to decide otherwise for somebody else's payload. Guessing wrong in this direction means
        // treating a genuinely different request as a duplicate, which is the one failure worse than
        // refusing a retry.
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    @DisplayName("a charset parameter on the content type does not matter")
    void contentTypeParametersAreIgnored() {
        String bare = fingerprints.of("POST", "/orders", "application/json", bytes("{\"a\":1}"));
        String withCharset = fingerprints.of("POST", "/orders", "application/json;charset=UTF-8",
                bytes("{\"a\":1}"));

        assertThat(withCharset).isEqualTo(bare);
    }

    @Test
    @DisplayName("the method and the path are part of the fingerprint")
    void methodAndPathMatter() {
        String post = fingerprints.of("POST", "/orders", "application/json", bytes("{\"a\":1}"));
        String patch = fingerprints.of("PATCH", "/orders", "application/json", bytes("{\"a\":1}"));
        String other = fingerprints.of("POST", "/invoices", "application/json", bytes("{\"a\":1}"));

        assertThat(post).isNotEqualTo(patch).isNotEqualTo(other);
    }

    @Test
    @DisplayName("a body that is not JSON is hashed as it arrived")
    void nonJsonBodyIsHashedRaw() {
        String first = fingerprints.of("POST", "/upload", "text/csv", bytes("a,b\n1,2\n"));
        String same = fingerprints.of("POST", "/upload", "text/csv", bytes("a,b\n1,2\n"));
        String different = fingerprints.of("POST", "/upload", "text/csv", bytes("a,b\n1,3\n"));

        assertThat(same).isEqualTo(first);
        assertThat(different).isNotEqualTo(first);
    }

    @Test
    @DisplayName("a body that claims to be JSON and is not still fingerprints, rather than failing")
    void malformedJsonDoesNotThrow() {
        String first = fingerprints.of("POST", "/orders", "application/json", bytes("{not json"));

        // Rejecting here would mean this module answering a malformed-body error before the handler that
        // owns that error has seen the request, in a different shape from the service's own pipeline.
        assertThat(first).isNotBlank();
        assertThat(fingerprints.of("POST", "/orders", "application/json", bytes("{not json")))
                .isEqualTo(first);
    }

    @Test
    @DisplayName("a payload fingerprint is scoped, so a scope rename cannot make an old claim match")
    void payloadFingerprintIsScoped() {
        byte[] payload = bytes("{\"event\":\"created\"}");

        assertThat(fingerprints.ofPayload("kafka:orders", payload))
                .isNotEqualTo(fingerprints.ofPayload("kafka:orders-v2", payload));
    }

    @Test
    @DisplayName("an empty body is stable and is not the same as a missing one on another path")
    void emptyBodyIsStable() {
        String first = fingerprints.of("POST", "/orders/1/cancel", null, new byte[0]);

        assertThat(fingerprints.of("POST", "/orders/1/cancel", null, null)).isEqualTo(first);
        assertThat(fingerprints.of("POST", "/orders/2/cancel", null, new byte[0])).isNotEqualTo(first);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
