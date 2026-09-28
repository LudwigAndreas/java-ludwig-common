package ru.ludwigandreas.export.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.api.ReportRequest;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.api.SortDirection;
import ru.ludwigandreas.export.api.SortKey;
import ru.ludwigandreas.export.entity.ExportReportOutput;
import ru.ludwigandreas.export.entity.ExportReportRun;
import ru.ludwigandreas.export.exception.ReportOutputUnavailableException;
import ru.ludwigandreas.export.exception.UnknownReportDefinitionException;
import ru.ludwigandreas.export.config.ExportProperties;
import ru.ludwigandreas.export.engine.DefaultExecutionPlanner;
import ru.ludwigandreas.export.i18n.ExportMessages;
import ru.ludwigandreas.export.lifecycle.ExportRunService;
import ru.ludwigandreas.export.lifecycle.ReportRequestService;
import ru.ludwigandreas.export.registry.ReportDefinitionRegistry;
import ru.ludwigandreas.export.registry.ReportWriterFactories;
import ru.ludwigandreas.export.repository.ExportReportOutputRepository;
import ru.ludwigandreas.export.repository.ExportReportRunRepository;
import ru.ludwigandreas.export.security.ReportAuthorities;
import ru.ludwigandreas.export.web.dto.ReportDefinitionResponse;
import ru.ludwigandreas.export.web.dto.ReportRunResponse;
import ru.ludwigandreas.export.web.dto.RunReportRequest;
import ru.ludwigandreas.webcore.operation.OperationLocationHeader;
import ru.ludwigandreas.webcore.operation.OperationResponses;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * The REST surface: request a report, poll it, download it, cancel it, and ask what exists.
 *
 * <h2>Every error goes through the problem pipeline</h2>
 *
 * <p>There is no {@code @ExceptionHandler} in this module and no {@code @RestControllerAdvice}.
 * Every failure is a {@code LocalizedException} that {@code web-core}'s single pipeline renders, so a
 * report's 403 is shaped like every other 403 in the service and is translated by the same bundles.
 * A module that shipped its own advice would render its errors slightly differently from the rest of
 * the API, which is the problem {@code web-core} exists to have solved once.
 *
 * <h2>The download re-checks the caller</h2>
 *
 * <p>Holding a run id is not authorisation to download its file. The endpoint checks that the caller
 * is the requester or holds the admin authority, every time - a run id in a URL is exactly the kind
 * of thing that ends up in a chat message, and the file behind it is a bulk extract.
 *
 * <p>{@code Content-Disposition} uses {@code filename*=UTF-8''...} so a Cyrillic report title
 * survives the trip. Spring's {@link ContentDisposition} builder emits both forms, which is what
 * older clients need.
 */
@Slf4j
@RestController
@Tag(name = "Reports", description = "Produce, poll and download tabular reports")
public class ReportRunController {

    /** The authority that may download and cancel somebody else's run. */
    public static final String ADMIN_AUTHORITY = "ROLE_EXPORT_ADMIN";

    /**
     * The configured base path, as a property placeholder for the mappings below.
     *
     * <p>A constant rather than the same placeholder written five times: an annotation needs a
     * compile-time constant, so the placeholder cannot be replaced by a field read, but it can at
     * least be spelled once. The suffixes below are then shared between the {@code @RequestMapping}
     * and the {@code Location} header, which is the property that matters - a mapping and a location
     * built from two separate literals drift apart, and the symptom is a 202 pointing at a 404.
     */
    private static final String BASE = "${ludwig.export.web.base-path:/api/v1/reports}";

    /** The collection a run is created in and polled under, relative to the base path. */
    static final String RUNS_PATH = "/runs";

    /** One run, relative to the base path. The status resource a 202 points at. */
    static final String RUN_PATH = RUNS_PATH + "/{runId}";

    private final ReportRequestService requests;
    private final ExportRunService lifecycle;
    private final ExportReportRunRepository runs;
    private final ExportReportOutputRepository outputs;
    private final ReportDefinitionRegistry registry;
    private final ReportWriterFactories formats;
    private final ReportAuthorities authorities;
    private final ReportSink sink;
    private final ExportMessages messages;
    private final ReportCaller caller;
    private final ExportProperties properties;

    // SUPPRESS CHECKSTYLE ParameterNumber - a Spring bean assembled by constructor injection, where
    // every argument is named by its own bean; there is no positional call site for the rule.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReportRunController(ReportRequestService requests, ExportRunService lifecycle,
                               ExportReportRunRepository runs, ExportReportOutputRepository outputs,
                               ReportDefinitionRegistry registry, ReportWriterFactories formats,
                               ReportAuthorities authorities, ReportSink sink, ExportMessages messages,
                               ReportCaller caller, ExportProperties properties) {
        this.requests = requests;
        this.lifecycle = lifecycle;
        this.runs = runs;
        this.outputs = outputs;
        this.registry = registry;
        this.formats = formats;
        this.authorities = authorities;
        this.sink = sink;
        this.messages = messages;
        this.caller = caller;
        this.properties = properties;
    }

    /**
     * Requests a report.
     *
     * <p>Answers 200 for a run that finished on the request thread and 202 for one that was queued.
     * The body is the same either way, so a client that always polls is correct and a client that
     * checks the status is faster - which is the right shape for an endpoint whose latency depends on
     * how much data the caller asked for. The platform contract permits the fast path explicitly and
     * requires only that the 200 carry a terminal envelope, which is what distinguishes the two cases
     * without reading the body.
     *
     * <p>The 202 now carries a {@code Location}. It did not before, which meant a client was told to
     * poll and not told where; this endpoint was the reason the contract exists.
     * {@code Location} rather than {@code Operation-Location} because the run <em>is</em> the
     * resource the client will read - see {@link OperationLocationHeader}.
     *
     * <p>The branch is on whether the run is <em>terminal</em>, not on whether it is {@code PENDING}.
     * Those differ in the case that matters: an idempotent resubmit hands back the run the first call
     * created, and that run may already be {@code RUNNING} on another instance. A 200 there would
     * claim the report was finished, and the earlier spelling of this branch did exactly that.
     */
    @PostMapping(BASE + "/{definitionKey}" + RUNS_PATH)
    @Operation(summary = "Request a report", description = "Runs it now if it is small, queues it otherwise")
    public ResponseEntity<ReportRunResponse> run(@PathVariable String definitionKey,
                                                 @Valid @RequestBody RunReportRequest body) {
        String principalId = caller.principalId();
        ExportReportRun run = requests.submit(toRequest(definitionKey, body), principalId);
        ReportRunResponse response = response(run);
        if (run.getStatus().isTerminal()) {
            return OperationResponses.completed(response.operation(), response);
        }
        return OperationResponses.accepted(response.operation(), response, statusUri(run.getId()),
                OperationLocationHeader.LOCATION);
    }

    /**
     * The state of a run, including its progress while it is still going.
     *
     * <p>A run that has not finished answers with {@code Retry-After}, from
     * {@code ludwig.export.web.retry-after}. Nothing in this repository emitted one before, so
     * every client invented its own interval and they all picked one second.
     *
     * @param runId the run
     * @return 200 with the run, and a Retry-After while it is still going
     */
    @GetMapping(BASE + RUN_PATH)
    @Operation(summary = "Poll a report run")
    public ResponseEntity<ReportRunResponse> status(@PathVariable UUID runId) {
        ReportRunResponse response = response(requireVisible(runId));
        return OperationResponses.poll(response.operation(), response,
                properties.getWeb().getRetryAfter());
    }

    /**
     * Downloads one of a run's files.
     *
     * <p>{@code ?format=} wins over {@code Accept}, because a report's format is a property of the
     * file that was produced rather than a negotiation: the bytes exist in exactly the formats the
     * run was asked for, and content negotiation implies the server could produce another.
     */
    @GetMapping(BASE + RUN_PATH + "/outputs/{format}")
    @Operation(summary = "Download a produced file")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID runId,
                                                        @PathVariable String format)
            throws IOException {
        ExportReportRun run = requireVisible(runId);
        ExportReportOutput output = outputs.findByRunIdAndFormatId(runId, format)
                .orElseThrow(() -> run.getStatus() == OperationStatus.SUCCEEDED
                        ? ReportOutputUnavailableException.expired(runId)
                        : ReportOutputUnavailableException.notReady(runId));
        if (!output.isDownloadable()) {
            throw ReportOutputUnavailableException.expired(runId);
        }
        InputStream bytes = sink.open(output.getSinkUri());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(output.getFileName(), StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(output.getSizeBytes());
        // The checksum travels with the file so a recipient can verify months later that the bytes
        // they still have are the bytes this run produced.
        headers.add("X-Report-Sha256", output.getSha256());
        headers.add("X-Report-Degraded",
                String.valueOf(run.getDegradedStages() != null && !run.getDegradedStages().isEmpty()));
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(output.getMediaType()))
                .body(new InputStreamResource(bytes));
    }

    /**
     * Asks a run to stop.
     *
     * <p>202 rather than 204: the run may be executing on another instance, and all this endpoint can
     * truthfully report is that the request was recorded. It takes effect within one window.
     */
    @DeleteMapping(BASE + RUN_PATH)
    @Operation(summary = "Cancel a report run")
    public ResponseEntity<ReportRunResponse> cancel(@PathVariable UUID runId) {
        ExportReportRun run = requireVisible(runId);
        lifecycle.requestCancel(runId);
        ReportRunResponse response = response(runs.findById(run.getId()).orElse(run));
        return OperationResponses.cancellationRequested(response.operation(), response);
    }

    /**
     * What this service can produce, as this caller may see it.
     *
     * <p>Filtered per caller rather than returned whole. A list that included reports they cannot run
     * would be a catalogue of what exists, which is information in its own right; and a list that
     * included columns they cannot export would produce a 403 the moment they used it.
     */
    @GetMapping(BASE + "/definitions")
    @Operation(summary = "List the reports this caller may run")
    public List<ReportDefinitionResponse> definitions(
            @RequestParam(required = false) String locale) {
        Set<String> granted = authorities.forPrincipal(caller.principalId());
        Locale resolved = locale == null ? caller.locale() : Locale.forLanguageTag(locale);
        return registry.definitions().stream()
                .filter(definition -> ReportDefinitionResponse.isVisibleTo(definition, granted))
                .map(definition -> ReportDefinitionResponse.of(definition, granted, messages, resolved))
                .toList();
    }

    /**
     * The run, if this caller is entitled to see it.
     *
     * <p>A run somebody else requested is reported as not found rather than as forbidden. The
     * distinction matters: a 403 confirms the run exists, which for a resource addressed by an opaque
     * id is the only thing an enumeration attempt could learn.
     */
    private ExportReportRun requireVisible(UUID runId) {
        ExportReportRun run = runs.findById(runId)
                .orElseThrow(() -> new UnknownReportDefinitionException(runId.toString()));
        String principalId = caller.principalId();
        if (run.getRequester().equals(principalId)) {
            return run;
        }
        if (authorities.forPrincipal(principalId).contains(ADMIN_AUTHORITY)) {
            return run;
        }
        throw new UnknownReportDefinitionException(runId.toString());
    }

    /**
     * The run, projected with links that resolve on this deployment.
     *
     * <p>The base path comes from the bound properties rather than from the placeholder above,
     * because a placeholder is only resolvable in an annotation; both read the same key, so a
     * deployment that remounts this module moves its mappings and its links together.
     */
    private ReportRunResponse response(ExportReportRun run) {
        return ReportRunResponse.of(run, properties.getWeb().getBasePath());
    }

    /** Where a client polls the run it was just handed. */
    private URI statusUri(UUID runId) {
        return URI.create(properties.getWeb().getBasePath()
                + RUN_PATH.replace("{runId}", runId.toString()));
    }

    private ReportRequest toRequest(String definitionKey, RunReportRequest body) {
        ReportDefinition<?, ?> definition = registry.require(definitionKey);
        List<ReportFormat> requested = body.formats().isEmpty()
                ? List.of(definition.getDefaultFormat())
                : body.formats().stream().map(formats::require).toList();
        return new ReportRequest(definitionKey, body.parameters(), body.columnIds(), body.filter(),
                body.sort().stream().map(this::toSortKey).toList(), requested, body.options(),
                body.locale() == null ? caller.locale() : Locale.forLanguageTag(body.locale()),
                body.timeZone() == null ? caller.zone()
                        : DefaultExecutionPlanner.zoneOf(body.timeZone()),
                body.savedReportId(), null);
    }

    private SortKey toSortKey(RunReportRequest.SortRequest sort) {
        return new SortKey(sort.columnId(), "desc".equalsIgnoreCase(sort.direction())
                ? SortDirection.DESC
                : SortDirection.ASC);
    }
}
