package ru.ludwigandreas.export.unit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.entity.ExportReportRun;
import ru.ludwigandreas.export.i18n.ExportMessages;
import ru.ludwigandreas.export.lifecycle.ExportRunService;
import ru.ludwigandreas.export.lifecycle.ReportRequestService;
import ru.ludwigandreas.export.registry.ReportDefinitionRegistry;
import ru.ludwigandreas.export.registry.ReportWriterFactories;
import ru.ludwigandreas.export.repository.ExportReportOutputRepository;
import ru.ludwigandreas.export.repository.ExportReportRunRepository;
import ru.ludwigandreas.export.security.ReportAuthorities;
import ru.ludwigandreas.export.web.ReportCaller;
import ru.ludwigandreas.export.web.ReportRunController;
import ru.ludwigandreas.webcore.operation.OperationHeaders;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * The HTTP contract of the run endpoints: the headers a client needs and did not used to get.
 *
 * <p>Standalone rather than a full context, because what is being asserted is the controller's own
 * response shape - the status code, the {@code Location} and the {@code Retry-After}. Everything
 * behind it is mocked; the lifecycle is already covered against a real database in
 * {@code ExportRunLifecycleIntegrationTest}.
 */
class ReportRunWebContractTest {

    private static final String BASE_PATH = "/api/v1/reports";

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static final String REQUESTER = "user-1";

    private final ReportRequestService requests = mock(ReportRequestService.class);

    private final ExportRunService lifecycle = mock(ExportRunService.class);

    private final ExportReportRunRepository runs = mock(ExportReportRunRepository.class);

    private final ReportAuthorities authorities = mock(ReportAuthorities.class);

    private final ReportCaller caller = mock(ReportCaller.class);

    private final ReportDefinitionRegistry registry = mock(ReportDefinitionRegistry.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ExportProperties properties = new ExportProperties();
        properties.getWeb().setBasePath(BASE_PATH);

        ReportRunController controller = new ReportRunController(requests, lifecycle, runs,
                mock(ExportReportOutputRepository.class), registry,
                mock(ReportWriterFactories.class), authorities, mock(ReportSink.class),
                mock(ExportMessages.class), caller, properties);

        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .addPlaceholderValue("ludwig.export.web.base-path", BASE_PATH)
                .build();

        when(caller.principalId()).thenReturn(REQUESTER);
        when(caller.locale()).thenReturn(Locale.ENGLISH);
        when(caller.zone()).thenReturn(ZoneOffset.UTC);
        when(authorities.forPrincipal(anyString())).thenReturn(Set.of());
        // doReturn rather than when/thenReturn: require() is declared with wildcard type arguments,
        // which thenReturn cannot be given a concrete definition for.
        doReturn(TestReports.orders().build()).when(registry).require(anyString());
    }

    @Test
    @DisplayName("a queued run answers 202 with a Location that points at its status resource")
    void queuedRunCarriesLocation() throws Exception {
        when(requests.submit(any(), anyString())).thenReturn(run(OperationStatus.PENDING));

        mockMvc.perform(post(BASE_PATH + "/catalog.orders/runs")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, BASE_PATH + "/runs/" + RUN_ID))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.operation.status").value("PENDING"));
    }

    @Test
    @DisplayName("the synchronous fast path still answers 200, now with a terminal envelope")
    void inlineRunAnswersTwoHundred() throws Exception {
        when(requests.submit(any(), anyString())).thenReturn(run(OperationStatus.SUCCEEDED));

        mockMvc.perform(post(BASE_PATH + "/catalog.orders/runs")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.operation.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.operation.result.href")
                        .value(BASE_PATH + "/runs/" + RUN_ID + "/outputs/csv"));
    }

    @Test
    @DisplayName("an idempotent resubmit of a still-running run answers 202, not 200")
    void resubmitOfARunningRunIsStillAccepted() throws Exception {
        // submit() hands back the run the first call created, which may already be RUNNING on
        // another instance. A 200 here would claim the report was finished.
        when(requests.submit(any(), anyString())).thenReturn(run(OperationStatus.RUNNING));

        mockMvc.perform(post(BASE_PATH + "/catalog.orders/runs")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, BASE_PATH + "/runs/" + RUN_ID));
    }

    @Test
    @DisplayName("polling a run that has not finished carries Retry-After")
    void pollCarriesRetryAfter() throws Exception {
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(OperationStatus.RUNNING)));

        mockMvc.perform(get(BASE_PATH + "/runs/" + RUN_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(OperationHeaders.RETRY_AFTER, "2"))
                .andExpect(jsonPath("$.operation.progress.completed").value(0));
    }

    @Test
    @DisplayName("polling a finished run carries no Retry-After and does carry a result")
    void terminalPollHasNoRetryAfter() throws Exception {
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(OperationStatus.SUCCEEDED)));

        mockMvc.perform(get(BASE_PATH + "/runs/" + RUN_ID))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(OperationHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.operation.result.href").exists())
                .andExpect(jsonPath("$.operation.result.alternatives[0].href")
                        .value(BASE_PATH + "/runs/" + RUN_ID + "/outputs/xlsx"));
    }

    @Test
    @DisplayName("an expired run is terminal, owes no result and is not reported as a failure")
    void expiredRunOwesNoResult() throws Exception {
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(OperationStatus.EXPIRED)));

        mockMvc.perform(get(BASE_PATH + "/runs/" + RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.status").value("EXPIRED"))
                .andExpect(jsonPath("$.operation.result").doesNotExist())
                .andExpect(jsonPath("$.operation.failure").doesNotExist());
    }

    @Test
    @DisplayName("cancelling answers 202, and cancelling a terminal run is not a 409")
    void cancelAnswersAccepted() throws Exception {
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(OperationStatus.RUNNING)));
        mockMvc.perform(delete(BASE_PATH + "/runs/" + RUN_ID))
                .andExpect(status().isAccepted());

        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run(OperationStatus.CANCELLED)));
        mockMvc.perform(delete(BASE_PATH + "/runs/" + RUN_ID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation.status").value("CANCELLED"));
    }

    private static ExportReportRun run(OperationStatus status) {
        ExportReportRun run = new ExportReportRun();
        run.setId(RUN_ID);
        run.setDefinitionKey("catalog.orders");
        run.setRequester(REQUESTER);
        run.setStatus(status);
        run.setFormats(List.of("csv", "xlsx"));
        run.setCreatedAt(Instant.parse("2026-09-27T09:00:00Z"));
        if (status.isTerminal()) {
            run.setFinishedAt(Instant.parse("2026-09-27T09:00:05Z"));
        }
        return run;
    }
}
