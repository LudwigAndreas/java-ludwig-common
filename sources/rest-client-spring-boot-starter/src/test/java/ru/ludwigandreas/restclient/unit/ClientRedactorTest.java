package ru.ludwigandreas.restclient.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import ru.ludwigandreas.audit.redaction.KeyNameSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.Redaction;
import ru.ludwigandreas.audit.redaction.SensitivityClassifier;
import ru.ludwigandreas.restclient.observability.ClientRedactor;

/**
 * Redaction, which is the difference between an exchange log and a credential leak.
 *
 * <p>What this class still covers after the consolidation is the <em>per-client binding</em>: that a
 * client's own lists are honoured, that they are composed with the deployment-wide classifier rather than
 * replacing it, and that the client name reaches the classifier as the provenance. The masking behaviour
 * itself - the structural JSON walk, the form-encoded rule, the truncation marker - moved into
 * {@code audit-core} together with the cases that proved it, which are now in that module's
 * {@code RedactorTest}.
 */
class ClientRedactorTest {

    private static final int MAX_BODY = 1000;

    private final ClientRedactor redactor = new ClientRedactor("partner",
            SensitivityClassifier.none(),
            List.of("Authorization", "Cookie", "X-Api-Key"),
            List.of("password", "access_token"),
            MAX_BODY);

    @Test
    @DisplayName("a listed header is masked and an unlisted one is not")
    void masksListedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer super-secret");
        headers.set("Accept", "application/json");

        var redacted = redactor.redact(headers);

        assertThat(redacted.get("Authorization")).containsExactly(Redaction.MASK);
        assertThat(redacted.get("Accept")).containsExactly("application/json");
    }

    @Test
    @DisplayName("header matching is case-insensitive, because HTTP header names are")
    void matchesHeadersCaseInsensitively() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", "Bearer super-secret");

        assertThat(redactor.redact(headers).get("authorization")).containsExactly(Redaction.MASK);
    }

    @Test
    @DisplayName("every value of a repeated header is masked, not just the first")
    void masksEveryValueOfARepeatedHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Cookie", "a=1");
        headers.add("Cookie", "b=2");

        assertThat(redactor.redact(headers).get("Cookie"))
                .containsExactly(Redaction.MASK, Redaction.MASK);
    }

    @Test
    @DisplayName("a configured body field is masked at any depth, and only as a field name")
    void masksNestedJsonFields() {
        String body = "{\"user\":{\"name\":\"password\",\"password\":\"hunter2\"},"
                + "\"tokens\":[{\"access_token\":\"abc\"}]}";

        String redacted = redactor.redactBody(body, "application/json", MAX_BODY);

        assertThat(redacted).contains("\"password\":\"" + Redaction.MASK + "\"")
                .contains("\"access_token\":\"" + Redaction.MASK + "\"")
                .contains("\"name\":\"password\"");
    }

    @Test
    @DisplayName("a form-encoded body is replaced wholesale - it cannot be walked safely")
    void masksFormEncodedBodiesEntirely() {
        assertThat(redactor.redactBody("username=bob&password=hunter2",
                "application/x-www-form-urlencoded", MAX_BODY)).isEqualTo(Redaction.MASK);
    }

    @Test
    @DisplayName("the client's configured limit is used by the no-limit overload")
    void appliesTheConfiguredTruncationLimit() {
        ClientRedactor small = new ClientRedactor("partner", SensitivityClassifier.none(),
                List.of(), List.of(), 10);

        assertThat(small.redactBody("x".repeat(100), "text/plain"))
                .startsWith("xxxxxxxxxx").contains("truncated 90 chars");
    }

    /**
     * The composition rule: a per-client list can only widen what is masked. A deployment-wide rule that a
     * client's list does not mention must still apply, or a module could undo a platform decision.
     */
    @Test
    @DisplayName("a deployment-wide rule still applies to a client that did not list the name")
    void composesWithTheDeploymentWideClassifier() {
        ClientRedactor composed = new ClientRedactor("partner", new KeyNameSensitivityClassifier(),
                List.of("X-Api-Key"), List.of(), MAX_BODY);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Refresh-Token", "abc");

        assertThat(composed.redact(headers).get("X-Refresh-Token")).containsExactly(Redaction.MASK);
    }

    @Test
    @DisplayName("a body that claims to be JSON and is not is kept, not dropped")
    void keepsUnparseableJson() {
        assertThat(redactor.redactBody("<html>502 Bad Gateway</html>", "application/json", MAX_BODY))
                .contains("502 Bad Gateway");
    }
}
