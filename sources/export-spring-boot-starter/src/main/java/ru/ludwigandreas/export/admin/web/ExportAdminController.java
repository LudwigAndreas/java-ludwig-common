package ru.ludwigandreas.export.admin.web;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.export.admin.ExportAdminOperations;
import ru.ludwigandreas.export.admin.service.ExportAdminService;
import ru.ludwigandreas.export.admin.service.ExportAdminService.Cancelled;
import ru.ludwigandreas.export.admin.service.ExportAdminService.RunView;
import ru.ludwigandreas.export.admin.service.ExportAdminService.SubscriptionView;
import ru.ludwigandreas.security.admin.AdminPage;
import ru.ludwigandreas.security.operation.RequiresOperation;
import ru.ludwigandreas.webcore.operation.OperationResponses;

/**
 * The {@code exports} administrative area: every requester's runs and subscriptions, and cancellation.
 */
@RestController
@RequestMapping("/admin/v1/exports")
public class ExportAdminController {

    private final ExportAdminService service;

    /**
     * Creates the controller.
     *
     * @param service the area's operations
     */
    public ExportAdminController(ExportAdminService service) {
        this.service = service;
    }

    /**
     * Runs matching the filter, newest first.
     *
     * @param filter an OData {@code $filter} over the run's filterable fields
     * @param cursor the previous page's cursor
     * @param size   the page size, at most 200
     * @return one page
     */
    @GetMapping("/runs")
    @RequiresOperation(ExportAdminOperations.RUN_READ)
    public AdminPage<RunView> runs(@RequestParam(name = "$filter", required = false) String filter,
                                   @RequestParam(required = false) String cursor,
                                   @RequestParam(required = false) Integer size) {
        return service.runs(filter, cursor, size);
    }

    /**
     * One run, without its output.
     *
     * @param runId the run
     * @return the run
     */
    @GetMapping("/runs/{runId}")
    @RequiresOperation(ExportAdminOperations.RUN_READ)
    public RunView run(@PathVariable UUID runId) {
        return service.run(runId);
    }

    /**
     * Requests cancellation of anybody's run; a terminal run answers with its envelope.
     *
     * @param runId the run
     * @return {@code 202} with the run
     */
    @PostMapping("/runs/{runId}/cancel")
    @RequiresOperation(ExportAdminOperations.RUN_CANCEL)
    public ResponseEntity<RunView> cancel(@PathVariable UUID runId) {
        Cancelled cancelled = service.cancel(runId);
        return OperationResponses.cancellationRequested(cancelled.envelope(), cancelled.view());
    }

    /**
     * Subscriptions matching the filter.
     *
     * @param owner  who created it
     * @param report which saved report it runs
     * @return the subscriptions
     */
    @GetMapping("/subscriptions")
    @RequiresOperation(ExportAdminOperations.SUBSCRIPTION_READ)
    public List<SubscriptionView> subscriptions(@RequestParam(required = false) String owner,
                                                @RequestParam(required = false) UUID report) {
        return service.subscriptions(owner, report);
    }
}
