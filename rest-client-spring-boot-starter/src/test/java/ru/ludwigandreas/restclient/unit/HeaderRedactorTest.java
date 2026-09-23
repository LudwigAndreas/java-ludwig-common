package ru.ludwigandreas.restclient.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import ru.ludwigandreas.restclient.observability.HeaderRedactor;

/** Redaction, which is the difference between an exchange log and a credential leak. */
class HeaderRedactorTest {

    private final HeaderRedactor redactor = new HeaderRedactor(
            List.of("Authorization", "Cookie", "X-Api-Key"),
            List.of("password", "access_token"),
            new ObjectMapper());

    @Test
    @DisplayName("a listed header is masked and an unlisted one is not")
    void masksListedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer super-secret");
        headers.set("Accept", "application/json");

        var redacted = redactor.redact(headers);

        assertThat(redacted.get("Authorization")).containsExactly("****");
        assertThat(redacted.get("Accept")).containsExactly("application/json");
    }

    @Test
    @DisplayName("header matching is case-insensitive, because HTTP header names are")
    void matchesHeadersCaseInsensitively() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("authorization", "Bearer super-secret");

        assertThat(redactor.redact(headers).get("authorization")).containsExactly("****");
    }

    @Test
    @DisplayName("every value of a repeated header is masked, not just the first")
    void masksEveryValueOfARepeatedHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Cookie", "a=1");
        headers.add("Cookie", "b=2");

        assertThat(redactor.redact(headers).get("Cookie")).containsExactly("****", "****");
    }

    @Test
    @DisplayName("a JSON field is masked at any depth, and only as a field name")
    void masksNestedJsonFields() {
        String body = "{\"user\":{\"name\":\"password\",\"password\":\"hunter2\"},"
                + "\"tokens\":[{\"access_token\":\"abc\"}]}";

        String redacted = redactor.redactBody(body, "application/json", 1000);

        assertThat(redacted).contains("\"password\":\"****\"")
                .contains("\"access_token\":\"****\"")
                // "password" as a VALUE is not a secret and must survive - a regex would have eaten it.
                .contains("\"name\":\"password\"");
    }

    @Test
    @DisplayName("a form-encoded body is replaced wholesale - it cannot be walked safely")
    void masksFormEncodedBodiesEntirely() {
        assertThat(redactor.redactBody("username=bob&password=hunter2",
                "application/x-www-form-urlencoded", 1000)).isEqualTo("****");
    }

    @Test
    @DisplayName("a long body is truncated with a marker saying how much was dropped")
    void truncatesWithAnExplicitMarker() {
        String body = "x".repeat(100);

        String redacted = redactor.redactBody(body, "text/plain", 10);

        assertThat(redacted).startsWith("xxxxxxxxxx").contains("truncated 90 chars");
    }

    @Test
    @DisplayName("a body that claims to be JSON and is not is kept, not dropped")
    void keepsUnparseableJson() {
        assertThat(redactor.redactBody("<html>502 Bad Gateway</html>", "application/json", 1000))
                .contains("502 Bad Gateway");
    }
}
