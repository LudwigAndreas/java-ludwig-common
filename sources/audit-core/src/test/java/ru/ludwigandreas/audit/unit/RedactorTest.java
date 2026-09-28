package ru.ludwigandreas.audit.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.audit.redaction.ConfiguredNamesSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.KeyNameSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.Redaction;
import ru.ludwigandreas.audit.redaction.Redactor;
import ru.ludwigandreas.audit.redaction.SensitivityClassifier;

/**
 * Ported from {@code rest-client-spring-boot-starter}'s {@code HeaderRedactorTest}, before the
 * implementation it covered was touched. Every case here was passing against {@code HeaderRedactor} and
 * has to keep passing: the structural JSON walk, the form-encoded rule and the truncation marker are the
 * capability a lowest-common-denominator merge of the three old redactors would have thrown away.
 */
class RedactorTest {

    private final Redactor redactor = new Redactor(
            new ConfiguredNamesSensitivityClassifier(
                    List.of("Authorization", "Cookie", "X-Api-Key", "password", "access_token")),
            new ObjectMapper());

    @Test
    @DisplayName("a listed header is masked and an unlisted one is not")
    void masksListedHeaders() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Authorization", List.of("Bearer super-secret"));
        headers.put("Accept", List.of("application/json"));

        Map<String, List<String>> redacted = redactor.redactHeaders("partner", headers);

        assertThat(redacted.get("Authorization")).containsExactly(Redaction.MASK);
        assertThat(redacted.get("Accept")).containsExactly("application/json");
    }

    @Test
    @DisplayName("header matching is case-insensitive, because HTTP header names are")
    void matchesHeadersCaseInsensitively() {
        Map<String, List<String>> headers = Map.of("authorization", List.of("Bearer super-secret"));

        assertThat(redactor.redactHeaders("partner", headers).get("authorization"))
                .containsExactly(Redaction.MASK);
    }

    @Test
    @DisplayName("every value of a repeated header is masked, not just the first")
    void masksEveryValueOfARepeatedHeader() {
        Map<String, List<String>> headers = Map.of("Cookie", List.of("a=1", "b=2"));

        assertThat(redactor.redactHeaders("partner", headers).get("Cookie"))
                .containsExactly(Redaction.MASK, Redaction.MASK);
    }

    @Test
    @DisplayName("a JSON field is masked at any depth, and only as a field name")
    void masksNestedJsonFields() {
        String body = "{\"user\":{\"name\":\"password\",\"password\":\"hunter2\"},"
                + "\"tokens\":[{\"access_token\":\"abc\"}]}";

        String redacted = redactor.redactBody("partner", body, "application/json", 1000);

        assertThat(redacted).contains("\"password\":\"" + Redaction.MASK + "\"")
                .contains("\"access_token\":\"" + Redaction.MASK + "\"")
                // "password" as a VALUE is not a secret and must survive - a regex would have eaten it.
                .contains("\"name\":\"password\"");
    }

    @Test
    @DisplayName("a form-encoded body is replaced wholesale - it cannot be walked safely")
    void masksFormEncodedBodiesEntirely() {
        assertThat(redactor.redactBody("partner", "username=bob&password=hunter2",
                "application/x-www-form-urlencoded", 1000)).isEqualTo(Redaction.MASK);
    }

    @Test
    @DisplayName("a long body is truncated with a marker saying how much was dropped")
    void truncatesWithAnExplicitMarker() {
        String body = "x".repeat(100);

        String redacted = redactor.redactBody("partner", body, "text/plain", 10);

        assertThat(redacted).startsWith("xxxxxxxxxx").contains("truncated 90 chars");
    }

    @Test
    @DisplayName("a body that claims to be JSON and is not is kept, not dropped")
    void keepsUnparseableJson() {
        assertThat(redactor.redactBody("partner", "<html>502 Bad Gateway</html>", "application/json", 1000))
                .contains("502 Bad Gateway");
    }

    /** A limit of zero has to mean "no limit configured", never "keep nothing". */
    @Test
    void aNonPositiveLimitDoesNotTruncate() {
        assertThat(Redactor.truncate("abcdef", 0)).isEqualTo("abcdef");
        assertThat(Redactor.truncate("abcdef", -1)).isEqualTo("abcdef");
    }

    @Test
    @DisplayName("a null value is left null rather than masked")
    void leavesNullAlone() {
        assertThat(redactor.redactValue("partner", "password", null)).isNull();
        assertThat(redactor.redactString("partner", "password", null)).isNull();
    }

    /** The attributes map is walked, because a credential two maps down is the one nobody notices. */
    @Test
    void masksNestedAttributeMapsAndLists() {
        Redactor byName = new Redactor(new KeyNameSensitivityClassifier(), new ObjectMapper());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("clientName", "partner");
        attributes.put("nested", Map.of("db.password", "hunter2", "db.host", "localhost"));
        // 'tokens' matches the secret-name heuristic, so the whole list is one masked value rather than
        // a list of masks: the length of a list of credentials is itself information.
        attributes.put("tokens", List.of("a", "b"));
        attributes.put("stages", List.of(Map.of("name", "enrich", "api-key", "k")));

        Map<String, Object> redacted = byName.redactAttributes(null, attributes);

        assertThat(redacted).containsEntry("clientName", "partner");
        assertThat(redacted.get("nested")).isEqualTo(
                Map.of("db.password", Redaction.MASK, "db.host", "localhost"));
        assertThat(redacted.get("tokens")).isEqualTo(Redaction.MASK);
        assertThat(redacted.get("stages")).isEqualTo(
                List.of(Map.of("name", "enrich", "api-key", Redaction.MASK)));
    }

    @Test
    void aRedactorWithNoClassifierMasksNothing() {
        Redactor open = new Redactor(SensitivityClassifier.none(), new ObjectMapper());

        assertThat(open.redactValue("vault:x", "password", "hunter2")).isEqualTo("hunter2");
    }
}
