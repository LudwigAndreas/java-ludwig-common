package ru.ludwigandreas.webcore.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import ru.ludwigandreas.webcore.operation.OperationHeaders;

/**
 * The operation contract through a real dispatch: the status codes, the headers and the problem
 * rendering a client actually sees.
 *
 * <p>Every assertion here is the fixed form of something this platform shipped wrong. Export
 * answered {@code 202} with no {@code Location}; nothing anywhere emitted {@code Retry-After}; and
 * cancelling twice was on its way to being a {@code 409}.
 */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class OperationContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestOperationController operations;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("a queued submit answers 202 with a Location and a non-terminal envelope")
    void queuedSubmitPointsAtItsStatusResource() throws Exception {
        MvcResult result = mockMvc.perform(post("/test/operations"))
                .andExpect(status().isAccepted())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        assertThat(location).isNotNull();

        mockMvc.perform(get(location)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the synchronous fast path answers 200 with a terminal envelope and a result")
    void inlineSubmitAnswersTwoHundred() throws Exception {
        mockMvc.perform(post("/test/operations").header("X-Inline", "true"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(OperationHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.result.href").exists());
    }

    @Test
    @DisplayName("a non-terminal poll carries Retry-After; a terminal one does not")
    void pollCarriesRetryAfterOnlyWhileRunning() throws Exception {
        String id = submit();

        mockMvc.perform(get("/test/operations/" + id))
                .andExpect(status().isOk())
                .andExpect(header().string(OperationHeaders.RETRY_AFTER,
                        String.valueOf(TestOperationController.POLL_INTERVAL.toSeconds())));

        operations.fail(id, "ludwig.web.error.internal");

        mockMvc.perform(get("/test/operations/" + id))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(OperationHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure.code").value("ludwig.web.error.internal"));
    }

    @Test
    @DisplayName("a progress with no denominator has no total member and does not render as 0")
    void progressWithoutATotalOmitsIt() throws Exception {
        String id = submit();

        MvcResult result = mockMvc.perform(get("/test/operations/" + id)).andReturn();
        JsonNode progress = json.readTree(result.getResponse().getContentAsString()).get("progress");

        assertThat(progress.has("completed")).isTrue();
        assertThat(progress.has("total")).isFalse();
    }

    @Test
    @DisplayName("a cooperative run observes a cancellation made over HTTP and stops")
    void cancellationStopsACooperativeRun() throws Exception {
        String id = submit();

        CompletableFuture<Long> run =
                CompletableFuture.supplyAsync(() -> operations.runUntilCancelled(id, Long.MAX_VALUE));

        mockMvc.perform(delete("/test/operations/" + id)).andExpect(status().isAccepted());

        assertThat(run.join()).isLessThan(Long.MAX_VALUE);

        mockMvc.perform(get("/test/operations/" + id))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("cancelling an already terminal operation returns the envelope, not a 409")
    void secondCancelIsNotAConflict() throws Exception {
        String id = submit();

        mockMvc.perform(delete("/test/operations/" + id)).andExpect(status().isAccepted());
        operations.runUntilCancelled(id, Long.MAX_VALUE);

        mockMvc.perform(delete("/test/operations/" + id))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("a repeated Idempotency-Key returns the same operation id, not a second run")
    void aRepeatedKeyReturnsTheSameOperation() throws Exception {
        String key = "key-" + System.nanoTime();

        String first = json.readTree(mockMvc.perform(post("/test/operations")
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        String second = json.readTree(mockMvc.perform(post("/test/operations")
                        .header("Idempotency-Key", key))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("an unknown operation id is a localized problem, not a stack trace")
    void unknownOperationIsAProblem() throws Exception {
        mockMvc.perform(get("/test/operations/no-such-operation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ludwig.web.error.operation-not-found"))
                .andExpect(jsonPath("$.operationId").value("no-such-operation"));
    }

    private String submit() throws Exception {
        MvcResult result = mockMvc.perform(post("/test/operations"))
                .andExpect(status().isAccepted())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }
}
