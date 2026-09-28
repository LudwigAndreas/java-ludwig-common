package ru.ludwigandreas.jira.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.jira.JiraClient;
import ru.ludwigandreas.jira.error.JiraException;
import ru.ludwigandreas.jira.http.JiraRequest;
import ru.ludwigandreas.jira.json.JiraJson;
import ru.ludwigandreas.jira.request.EstimateAdjustment;
import ru.ludwigandreas.jira.request.IssueInput;
import ru.ludwigandreas.jira.request.WorklogInput;
import ru.ludwigandreas.jira.model.issue.Attachment;
import ru.ludwigandreas.jira.structure.model.ForestSpec;

/**
 * Asserts the URLs and payloads this client puts on the wire, which is the part of a REST client that no
 * amount of unit-testing the models can cover and that a real Jira would only report as a 400.
 */
class ApiContractTest {

    private final ObjectMapper mapper = JiraJson.defaultObjectMapper();

    private JiraClient clientFor(RecordingTransport transport) {
        return JiraClient.builder()
                .baseUrl("https://jira.example.com")
                .personalAccessToken("t")
                .transport(transport)
                .retryPolicy(ru.ludwigandreas.jira.http.RetryPolicy.none())
                .build();
    }

    private JsonNode bodyOf(JiraRequest request) throws Exception {
        return mapper.readTree(new String(request.body().orElseThrow().content(), StandardCharsets.UTF_8));
    }

    @Test
    void searchGoesToThePostEndpointWithTheQueryInTheBody() throws Exception {
        RecordingTransport transport = new RecordingTransport()
                .respondWith(200, "{\"startAt\":0,\"maxResults\":50,\"total\":0,\"issues\":[]}");

        try (JiraClient client = clientFor(transport)) {
            client.search().search(ru.ludwigandreas.jira.request.SearchRequest
                    .of("project = OPS")
                    .fields("summary", "status")
                    .withFieldMetadata()
                    .maxResults(100)
                    .build());
        }

        assertThat(transport.lastRequest().uri()).hasToString("https://jira.example.com/rest/api/2/search");
        JsonNode body = bodyOf(transport.lastRequest());
        assertThat(body.path("jql").asText()).isEqualTo("project = OPS");
        assertThat(body.path("maxResults").asInt()).isEqualTo(100);
        assertThat(body.path("fields")).hasSize(2);
        assertThat(body.path("expand")).hasSize(2);
    }

    @Test
    void transitionByNameLooksTheIdUpOnTheIssueRatherThanHardCodingIt() throws Exception {
        RecordingTransport transport = new RecordingTransport()
                .respondWith(200, "{\"transitions\":[{\"id\":\"31\",\"name\":\"Resolve Issue\"}]}")
                .respondWith(204, "");

        try (JiraClient client = clientFor(transport)) {
            client.issues().transitionByName("OPS-1", "resolve issue",
                    IssueInput.builder().resolution("Fixed").build());
        }

        assertThat(transport.requests()).hasSize(2);
        assertThat(transport.lastRequest().uri())
                .hasToString("https://jira.example.com/rest/api/2/issue/OPS-1/transitions");
        JsonNode body = bodyOf(transport.lastRequest());
        assertThat(body.path("transition").path("id").asText()).isEqualTo("31");
        assertThat(body.path("fields").path("resolution").path("name").asText()).isEqualTo("Fixed");
    }

    @Test
    void transitionByNameSaysWhatWasAvailableWhenTheNameDoesNotMatch() {
        RecordingTransport transport = new RecordingTransport()
                .respondWith(200, "{\"transitions\":[{\"id\":\"31\",\"name\":\"Resolve Issue\"}]}");

        try (JiraClient client = clientFor(transport)) {
            assertThatThrownBy(() -> client.issues().transitionByName("OPS-1", "Close", null))
                    .isInstanceOf(JiraException.class)
                    .hasMessageContaining("Resolve Issue");
        }
    }

    @Test
    void refusesAnEmptyUpdateRatherThanLettingJiraAnswer400() {
        RecordingTransport transport = new RecordingTransport();

        try (JiraClient client = clientFor(transport)) {
            assertThatThrownBy(() -> client.issues().update("OPS-1", IssueInput.builder().build()))
                    .isInstanceOf(JiraException.class)
                    .hasMessageContaining("empty update");
        }

        assertThat(transport.requests()).isEmpty();
    }

    @Test
    void suppressesNotificationsOnlyWhenAskedTo() {
        RecordingTransport transport = new RecordingTransport().respondWith(204, "").respondWith(204, "");

        try (JiraClient client = clientFor(transport)) {
            IssueInput input = IssueInput.builder().summary("x").build();
            client.issues().update("OPS-1", input);
            client.issues().update("OPS-1", input, false);
        }

        assertThat(transport.requests().get(0).uri().getQuery()).isNull();
        assertThat(transport.requests().get(1).uri().getQuery()).isEqualTo("notifyUsers=false");
    }

    @Test
    void sendsTheEstimateAdjustmentAsQueryParametersNotInTheWorklogBody() {
        RecordingTransport transport = new RecordingTransport().respondWith(201, "{\"id\":\"1\"}");

        try (JiraClient client = clientFor(transport)) {
            client.worklogs().add("OPS-1",
                    WorklogInput.of("3h", OffsetDateTime.of(2024, 1, 15, 9, 0, 0, 0, ZoneOffset.UTC)),
                    EstimateAdjustment.setTo("1d"));
        }

        assertThat(transport.lastRequest().uri().getQuery()).isEqualTo("adjustEstimate=new&newEstimate=1d");
    }

    @Test
    void rejectsAWorklogCarryingBothDurationFormsBeforeJiraDoes() {
        assertThatThrownBy(() -> new WorklogInput(null, null, "3h", 10800L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not both");
        assertThatThrownBy(() -> new WorklogInput(null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uploadsAnAttachmentAsMultipartWithTheXsrfHeader() {
        RecordingTransport transport = new RecordingTransport().respondWith(200, "[{\"id\":\"1\"}]");

        try (JiraClient client = clientFor(transport)) {
            client.attachments().upload("OPS-1", "report \"q1\".pdf", "application/pdf", new byte[] {1, 2, 3});
        }

        JiraRequest request = transport.lastRequest();
        assertThat(request.headers()).containsEntry("X-Atlassian-Token", "no-check");
        String body = new String(request.body().orElseThrow().content(), StandardCharsets.UTF_8);
        assertThat(request.body().orElseThrow().contentType()).startsWith("multipart/form-data; boundary=");
        assertThat(body).contains("name=\"file\"").contains("filename=\"report \\\"q1\\\".pdf\"");
    }

    @Test
    void readsAStructureForestWithAPostSoALongSpecIsNotTruncatedByAProxy() throws Exception {
        RecordingTransport transport = new RecordingTransport().respondWith(200,
                "{\"formula\":\"1:0:100\",\"itemTypes\":{},\"version\":{\"signature\":7,\"version\":3}}");

        try (JiraClient client = clientFor(transport)) {
            var forest = client.forests().readStructure(113);
            assertThat(forest.rows()).singleElement()
                    .satisfies(row -> assertThat(row.issueId()).contains(100L));
            assertThat(forest.version().version()).isEqualTo(3);
        }

        assertThat(transport.lastRequest().uri())
                .hasToString("https://jira.example.com/rest/structure/2.0/forest/latest");
        assertThat(bodyOf(transport.lastRequest()).path("structureId").asInt()).isEqualTo(113);
    }

    @Test
    void quotesTheObservedForestVersionOnAnUpdateSoAConcurrentEditIsRejected() throws Exception {
        RecordingTransport transport = new RecordingTransport()
                .respondWith(200, "{\"formula\":\"1:0:100\",\"version\":{\"signature\":7,\"version\":3}}")
                .respondWith(200, "{\"successfulActions\":1,\"version\":{\"signature\":7,\"version\":4}}");

        try (JiraClient client = clientFor(transport)) {
            client.forests().move(ForestSpec.structure(113), 1L, 0L, 0L);
        }

        JsonNode body = bodyOf(transport.lastRequest());
        assertThat(body.path("version").path("version").asInt()).isEqualTo(3);
        assertThat(body.path("actions").get(0).path("action").asText()).isEqualTo("move");
    }

    @Test
    void pairsStructuresPlaceholderRowIdsWithTheOnesItAssigned() {
        RecordingTransport transport = new RecordingTransport()
                .respondWith(200, "{\"formula\":\"\",\"version\":{\"signature\":1,\"version\":1}}")
                .respondWith(200, "{\"successfulActions\":1,\"oldRowIds\":[-100,-101],"
                        + "\"newRowIds\":[5001,5002],\"version\":{\"signature\":1,\"version\":2}}");

        try (JiraClient client = clientFor(transport)) {
            var result = client.forests().addIssues(ForestSpec.structure(113), 0L, List.of(10L, 11L));
            assertThat(result.assignedRowIds()).containsEntry(-100L, 5001L).containsEntry(-101L, 5002L);
        }
    }

    @Test
    void givesEachAddedStructureRowItsOwnPlaceholderId() throws Exception {
        RecordingTransport transport = new RecordingTransport()
                .respondWith(200, "{\"formula\":\"\",\"version\":{\"signature\":1,\"version\":1}}")
                .respondWith(200, "{\"successfulActions\":1}");

        try (JiraClient client = clientFor(transport)) {
            client.forests().addIssues(ForestSpec.structure(113), 0L, List.of(10L, 11L, 12L));
        }

        String formula = bodyOf(transport.lastRequest()).path("actions").get(0).path("forest").asText();
        assertThat(formula).isEqualTo("-1:0:10,-2:0:11,-3:0:12");
    }

    @Test
    void refusesToSendCredentialsToAContentUrlOnAnotherHost() {
        // Jira builds the content URL from its own configured base URL, which is routinely stale behind a
        // reverse proxy. Following it blindly would leak the token to whatever host that setting names.
        RecordingTransport transport = new RecordingTransport();
        Attachment elsewhere = new Attachment(null, "9", "a.pdf", null, null, 3L, "application/pdf",
                "https://other.example.com/secure/attachment/9/a.pdf", null);

        try (JiraClient client = clientFor(transport)) {
            assertThatThrownBy(() -> client.attachments().download(elsewhere))
                    .isInstanceOf(JiraException.class)
                    .hasMessageContaining("not under this client's base URL");
        }

        assertThat(transport.requests()).isEmpty();
    }

    @Test
    void downloadsAnAttachmentThroughTheClientsOwnBaseUrl() {
        RecordingTransport transport = new RecordingTransport().respondWith(200, "PDF");
        Attachment here = new Attachment(null, "9", "a.pdf", null, null, 3L, "application/pdf",
                "https://jira.example.com/secure/attachment/9/a.pdf", null);

        try (JiraClient client = clientFor(transport)) {
            assertThat(client.attachments().download(here)).asString().isEqualTo("PDF");
        }

        assertThat(transport.lastRequest().uri())
                .hasToString("https://jira.example.com/secure/attachment/9/a.pdf");
    }

    @Test
    void roundTripsAForestSpecificationThroughJson() throws Exception {
        ForestSpec spec = ForestSpec.structure(113)
                .withTransformation(java.util.Map.of("transformation", "sort"));

        String json = mapper.writeValueAsString(spec);
        ForestSpec parsed = mapper.readValue(json, ForestSpec.class);

        assertThat(parsed.structureId()).contains(113L);
        assertThat(parsed.properties()).containsKey("transformations");
        assertThat(mapper.writeValueAsString(parsed)).isEqualTo(json);
    }

    @Test
    void scopesCreateMetaToOneProjectAndTypeBecauseUnscopedItIsUnusable() {
        RecordingTransport transport = new RecordingTransport().respondWith(200, "{\"projects\":[]}");

        try (JiraClient client = clientFor(transport)) {
            client.issues().createMeta("OPS", "Task");
        }

        assertThat(transport.lastRequest().uri().getQuery())
                .isEqualTo("projectKeys=OPS&issuetypeNames=Task&expand=projects.issuetypes.fields");
    }

    @Test
    void reachesAnUnmodelledEndpointThroughTheRawClientWithAuthenticationIntact() {
        RecordingTransport transport = new RecordingTransport().respondWith(200, "[{\"id\":1}]");

        try (JiraClient client = clientFor(transport)) {
            JsonNode boards = client.rest()
                    .get("/rest/agile/1.0/board")
                    .query("projectKeyOrId", "OPS")
                    .operation("agile.board.list")
                    .asTree();
            assertThat(boards).hasSize(1);
        }

        assertThat(transport.lastRequest().uri())
                .hasToString("https://jira.example.com/rest/agile/1.0/board?projectKeyOrId=OPS");
        assertThat(transport.lastRequest().headers()).containsEntry("Authorization", "Bearer t");
        assertThat(transport.lastRequest().operation()).isEqualTo("agile.board.list");
    }
}
