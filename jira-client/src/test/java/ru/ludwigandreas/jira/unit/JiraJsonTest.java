package ru.ludwigandreas.jira.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.ludwigandreas.jira.error.JiraSerializationException;
import ru.ludwigandreas.jira.json.JiraJson;
import ru.ludwigandreas.jira.model.issue.Issue;

class JiraJsonTest {

    private final JiraJson json = JiraJson.createDefault();
    private final ObjectMapper mapper = json.objectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
            "2024-01-15T10:30:00.000+0300",
            "2024-01-15T10:30:00.000+03:00",
            "2024-01-15T10:30:00+0300",
    })
    void readsEveryOffsetSpellingJiraEmits(String raw) throws Exception {
        // The stock Jackson deserializer rejects the +0300 form outright, which would break every issue read.
        OffsetDateTime parsed = mapper.readValue("\"" + raw + "\"", OffsetDateTime.class);

        assertThat(parsed.toInstant())
                .isEqualTo(OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.ofHours(3)).toInstant());
    }

    @Test
    void writesTheSingleFormJirasOwnParserAccepts() throws Exception {
        OffsetDateTime when = OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.ofHours(3));

        assertThat(mapper.writeValueAsString(when)).isEqualTo("\"2024-01-15T10:30:00.000+0300\"");
    }

    @Test
    void ignoresFieldsTheModelDoesNotDeclareSoAJiraUpgradeIsNotAnOutage() {
        byte[] body = ("{\"key\":\"ABC-1\",\"somethingJira10Added\":{\"a\":1},"
                + "\"fields\":{\"summary\":\"s\",\"customfield_10001\":42}}")
                .getBytes(StandardCharsets.UTF_8);

        Issue issue = json.read(body, Issue.class);

        assertThat(issue.key()).isEqualTo("ABC-1");
        assertThat(issue.fieldsOrEmpty().summary()).isEqualTo("s");
        assertThat(issue.fieldsOrEmpty().raw("customfield_10001")).isPresent();
    }

    @Test
    void keepsCustomFieldsSeparateFromTheTypedSystemFields() {
        byte[] body = ("{\"key\":\"ABC-1\",\"fields\":{\"summary\":\"s\",\"customfield_10001\":42,"
                + "\"customfield_10002\":null}}").getBytes(StandardCharsets.UTF_8);

        var fields = json.read(body, Issue.class).fieldsOrEmpty();

        assertThat(fields.customFields()).containsOnlyKeys("customfield_10001", "customfield_10002");
        assertThat(fields.raw("customfield_10001").orElseThrow().asInt()).isEqualTo(42);
        assertThat(fields.raw("customfield_10002")).isEmpty();
        assertThat(fields.has("customfield_10002")).isTrue();
    }

    @Test
    void quotesTheOffendingBodyWhenItCannotBeParsed() {
        byte[] html = "<html><body>503 Service Unavailable</body></html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> json.read(html, Issue.class))
                .isInstanceOf(JiraSerializationException.class)
                .hasMessageContaining("503 Service Unavailable");
    }
}
