package ru.ludwigandreas.jira.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.jira.json.JiraJson;
import ru.ludwigandreas.jira.request.IssueInput;

class IssueInputTest {

    private final ObjectMapper mapper = JiraJson.defaultObjectMapper();

    @Test
    void buildsTheFieldsSectionForACreate() throws Exception {
        String json = mapper.writeValueAsString(IssueInput.builder()
                .project("OPS")
                .issueType("Task")
                .summary("Rotate the certificate")
                .dueDate(LocalDate.of(2026, 3, 1))
                .labels("infra", "tls")
                .build());

        JsonNode fields = mapper.readTree(json).path("fields");
        assertThat(fields.path("project").path("key").asText()).isEqualTo("OPS");
        assertThat(fields.path("issuetype").path("name").asText()).isEqualTo("Task");
        assertThat(fields.path("summary").asText()).isEqualTo("Rotate the certificate");
        assertThat(fields.path("duedate").asText()).isEqualTo("2026-03-01");
        assertThat(fields.path("labels")).hasSize(2);
    }

    @Test
    void putsAddAndRemoveIntoTheUpdateSectionSoConcurrentChangesAreNotLost() throws Exception {
        String json = mapper.writeValueAsString(IssueInput.builder()
                .addLabel("urgent")
                .removeLabel("stale")
                .build());

        JsonNode labels = mapper.readTree(json).path("update").path("labels");
        assertThat(labels).hasSize(2);
        assertThat(labels.get(0).path("add").asText()).isEqualTo("urgent");
        assertThat(labels.get(1).path("remove").asText()).isEqualTo("stale");
        assertThat(mapper.readTree(json).has("fields")).isFalse();
    }

    @Test
    void omitsAnUnsetOptionalRatherThanWritingItAsNull() throws Exception {
        String json = mapper.writeValueAsString(IssueInput.builder().summary("only this").build());

        assertThat(mapper.readTree(json).path("fields").has("priority")).isFalse();
    }

    @Test
    void writesAnExplicitNullWhenTheCallerAsksToClearAField() throws Exception {
        // A Java null would be dropped by the mapper's NON_NULL inclusion and silently leave the field alone.
        String json = mapper.writeValueAsString(IssueInput.builder().clear("customfield_10001").build());

        assertThat(mapper.readTree(json).path("fields").path("customfield_10001").isNull()).isTrue();
    }

    @Test
    void unassignsByClearingRatherThanByDroppingTheKey() throws Exception {
        String json = mapper.writeValueAsString(IssueInput.builder().assignee(null).build());

        assertThat(mapper.readTree(json).path("fields").path("assignee").isNull()).isTrue();
    }

    @Test
    void usesTheSentinelUsernameForTheProjectDefaultAssignee() throws Exception {
        String json = mapper.writeValueAsString(IssueInput.builder().assignToDefault().build());

        assertThat(mapper.readTree(json).path("fields").path("assignee").path("name").asText()).isEqualTo("-1");
    }

    @Test
    void reportsAPayloadThatWouldChangeNothing() {
        assertThat(IssueInput.builder().build().isEmpty()).isTrue();
        assertThat(IssueInput.builder().summary("x").build().isEmpty()).isFalse();
        assertThat(IssueInput.builder().addLabel("x").build().isEmpty()).isFalse();
    }

    @Test
    void carriesACommentAlongsideTheFieldChange() throws Exception {
        String json = mapper.writeValueAsString(IssueInput.builder()
                .summary("new")
                .addComment("done by automation")
                .build());

        assertThat(mapper.readTree(json).path("update").path("comment").get(0).path("add").path("body").asText())
                .isEqualTo("done by automation");
    }
}
