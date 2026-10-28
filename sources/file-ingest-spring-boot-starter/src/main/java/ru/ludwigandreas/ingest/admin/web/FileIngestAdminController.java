package ru.ludwigandreas.ingest.admin.web;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.ingest.admin.FileIngestAdminOperations;
import ru.ludwigandreas.ingest.admin.service.FileIngestAdminService;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.webcore.operation.OperationLocationHeader;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationResponses;

/**
 * The {@code fileingest} administrative area: tasks, their runs, and the manual trigger.
 */
@RestController
@RequestMapping("/admin/v1/fileingest/tasks")
public class FileIngestAdminController {

    private static final String TASKS = "/admin/v1/fileingest/tasks/";

    private final FileIngestAdminService service;

    /**
     * Creates the controller.
     *
     * @param service the area's operations
     */
    public FileIngestAdminController(FileIngestAdminService service) {
        this.service = service;
    }

    /**
     * Every task, with its recent runs.
     *
     * @return one entry per task
     */
    @GetMapping
    @AdminAccess(FileIngestAdminOperations.READ)
    public Map<String, Object> tasks() {
        return service.tasks();
    }

    /**
     * One task.
     *
     * @param task the task name
     * @return the task
     */
    @GetMapping("/{task}")
    @AdminAccess(FileIngestAdminOperations.READ)
    public Map<String, Object> task(@PathVariable String task) {
        return service.task(task);
    }

    /**
     * One run: its envelope, checkpoint, counts and quarantine sample, without row contents.
     *
     * @param task  the task name
     * @param runId the run
     * @return the run
     */
    @GetMapping("/{task}/runs/{runId}")
    @AdminAccess(FileIngestAdminOperations.READ)
    public Map<String, Object> run(@PathVariable String task, @PathVariable UUID runId) {
        return service.run(task, runId);
    }

    /**
     * Triggers a pass on this instance.
     *
     * @param task the task name
     * @return {@code 202} with the run's envelope and the run as its status resource; {@code 409} when it is running
     */
    @PostMapping("/{task}/runs")
    @AdminAccess(FileIngestAdminOperations.OPERATE)
    public ResponseEntity<OperationResponse> trigger(@PathVariable String task) {
        OperationResponse operation = service.trigger(task);
        // A pass that found nothing new to ingest writes no run; the task is then the status resource.
        if (operation == null) {
            return ResponseEntity.accepted().header("Location", TASKS + task).build();
        }
        return OperationResponses.accepted(operation, URI.create(TASKS + task + "/runs/" + operation.id()),
                OperationLocationHeader.LOCATION);
    }
}
