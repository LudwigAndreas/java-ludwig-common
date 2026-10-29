package ru.ludwigandreas.reconciliation.admin.web;

import static ru.ludwigandreas.reconciliation.admin.ReconciliationAdminOperations.ENUMERATION_NOT_FOUND;
import static ru.ludwigandreas.reconciliation.admin.ReconciliationAdminOperations.TASK_NOT_FOUND;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.reconciliation.admin.ReconciliationAdminOperations;
import ru.ludwigandreas.reconciliation.admin.service.ReconciliationAdminService;
import ru.ludwigandreas.reconciliation.admin.service.RunSnapshot;
import ru.ludwigandreas.reconciliation.admin.service.RunViews.RunView;
import ru.ludwigandreas.reconciliation.admin.service.RunViews;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminObject;
import ru.ludwigandreas.security.admin.AdminPage;
import ru.ludwigandreas.webcore.operation.OperationLocationHeader;
import ru.ludwigandreas.webcore.operation.OperationResponses;

/**
 * The {@code reconciliation} administrative area: every action the reconciliation actuator endpoint offered, each
 * as its own resource.
 *
 * <p>A scope key that contains {@code /} must be percent-encoded by the caller, as it had to be for the actuator
 * endpoint; a deployment whose firewall rejects encoded slashes cannot address such a scope here.
 */
@RestController
@RequestMapping("/admin/v1/reconciliation")
public class ReconciliationAdminController {

    private static final String TASKS = "/admin/v1/reconciliation/tasks/";

    /** How often a client should poll a run that has not finished. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);

    private final ReconciliationAdminService service;

    /**
     * Creates the controller.
     *
     * @param service the area's operations
     */
    public ReconciliationAdminController(ReconciliationAdminService service) {
        this.service = service;
    }

    /**
     * Every task, quota and enumeration.
     *
     * @return the overview
     */
    @GetMapping("/tasks")
    @AdminAccess(ReconciliationAdminOperations.READ)
    public Map<String, Object> overview() {
        return service.overview();
    }

    /**
     * One task or enumeration in detail.
     *
     * @param task the name
     * @return the detail
     */
    @GetMapping("/tasks/{task}")
    @AdminAccess(ReconciliationAdminOperations.READ)
    public Map<String, Object> task(@PathVariable @AdminObject(notFound = TASK_NOT_FOUND) String task) {
        return service.task(task);
    }

    /**
     * One scope of an enumeration.
     *
     * @param task  the enumeration
     * @param scope the scope key
     * @return the scope view
     */
    @GetMapping("/tasks/{task}/scopes/{scope}")
    @AdminAccess(ReconciliationAdminOperations.READ)
    public Map<String, Object> scope(@PathVariable @AdminObject(notFound = ENUMERATION_NOT_FOUND) String task,
                                     @PathVariable String scope) {
        return service.scope(task, scope);
    }

    /**
     * Starts a run of the task's pass, or a dry run, and answers at once with the run's envelope.
     *
     * <p>A dry run, and a manual run of a key-addressed task, become a recorded run: {@code 202} with its envelope
     * and {@code Location} naming the run resource. An enumeration's walk, and the submit pass of an asynchronous
     * remote-job task, are not fetch runs of their own and answer {@code 200} with what the pass did, as before.
     *
     * @param task    the task or enumeration
     * @param request the tier and whether to dry-run; may be empty
     * @return {@code 202} with the run, or {@code 200} with the pass's result
     */
    @PostMapping("/tasks/{task}/runs")
    @AdminAccess(ReconciliationAdminOperations.OPERATE)
    public ResponseEntity<?> run(@PathVariable @AdminObject String task,
                                 @RequestBody(required = false) RunRequest request) {
        RunRequest run = request == null ? new RunRequest(null, false) : request;
        if (!service.recordsRun(task, run.dryRun())) {
            return ResponseEntity.ok(service.runPass(task));
        }
        return accepted(service.startRun(task, run.tier(), run.dryRun()));
    }

    /**
     * Starts a fetch of an explicit set of keys.
     *
     * @param task    the task
     * @param request the keys
     * @return {@code 202} with the run
     */
    @PostMapping("/tasks/{task}/backfills")
    @AdminAccess(ReconciliationAdminOperations.OPERATE)
    public ResponseEntity<RunView> backfill(@PathVariable @AdminObject String task,
                                                     @RequestBody BackfillRequest request) {
        return accepted(service.backfill(task, request.keys()));
    }

    /**
     * A task's runs, newest first.
     *
     * @param task   the task
     * @param filter an OData {@code $filter} over the run's filterable fields
     * @param cursor the previous page's cursor
     * @param size   the page size
     * @return the page
     */
    @GetMapping("/tasks/{task}/runs")
    @AdminAccess(ReconciliationAdminOperations.READ)
    public AdminPage<RunView> runs(@PathVariable @AdminObject(notFound = TASK_NOT_FOUND) String task,
                                   @RequestParam(name = "$filter", required = false) String filter,
                                   @RequestParam(required = false) String cursor,
                                   @RequestParam(required = false) Integer size) {
        AdminPage<RunSnapshot> page = service.runs(task, filter, cursor, size);
        return new AdminPage<>(page.items().stream().map(run -> RunViews.toView(run, runHref(run))).toList(),
                page.next());
    }

    /**
     * One run, as a status resource.
     *
     * @param task  the task
     * @param runId the run
     * @return the run, with {@code Retry-After} while it is not finished
     */
    @GetMapping("/tasks/{task}/runs/{runId}")
    @AdminAccess(ReconciliationAdminOperations.READ)
    public ResponseEntity<RunView> runResource(@PathVariable @AdminObject(notFound = TASK_NOT_FOUND) String task,
                                               @PathVariable UUID runId) {
        RunView view = RunViews.toView(service.run(task, runId), TASKS + task + "/runs/" + runId);
        return OperationResponses.poll(view.operation(), view, POLL_INTERVAL);
    }

    /**
     * Clears a task's cursor and watermark.
     *
     * @param task    the task
     * @param request the tier; may be empty
     * @return what was reset
     */
    @PostMapping("/tasks/{task}/cursor/reset")
    @AdminAccess(ReconciliationAdminOperations.OPERATE)
    public Map<String, Object> resetCursor(@PathVariable @AdminObject String task,
                                           @RequestBody(required = false) TierRequest request) {
        return service.resetCursor(task, request == null ? null : request.tier());
    }

    /**
     * Requeues a task's quarantined records.
     *
     * @param task the task
     * @return how many were requeued
     */
    @PostMapping("/tasks/{task}/quarantine/requeue")
    @AdminAccess(ReconciliationAdminOperations.OPERATE)
    public Map<String, Object> requeueQuarantined(@PathVariable @AdminObject String task) {
        return service.requeueQuarantined(task);
    }

    /**
     * Requeues the records a remote job covered.
     *
     * @param task  the task
     * @param jobId the remote job
     * @return how many were requeued
     */
    @PostMapping("/tasks/{task}/remotejobs/{jobId}/requeue")
    @AdminAccess(ReconciliationAdminOperations.OPERATE)
    public Map<String, Object> requeueJob(@PathVariable @AdminObject String task, @PathVariable UUID jobId) {
        return service.requeueJob(task, jobId);
    }

    /**
     * Marks a remote job for cancellation at the partner.
     *
     * @param task  the task
     * @param jobId the remote job
     * @return what was marked
     */
    @PostMapping("/tasks/{task}/remotejobs/{jobId}/cancel")
    @AdminAccess(ReconciliationAdminOperations.PRIVILEGED)
    public Map<String, Object> cancelJob(@PathVariable @AdminObject String task, @PathVariable UUID jobId) {
        return service.cancelJob(task, jobId);
    }

    /**
     * Deletes a quota lease.
     *
     * @param leaseId the lease
     * @return what was released
     */
    @PostMapping("/leases/{leaseId}/release")
    @AdminAccess(ReconciliationAdminOperations.PRIVILEGED)
    public Map<String, Object> releaseLease(@PathVariable UUID leaseId) {
        return service.releaseLease(leaseId);
    }

    /**
     * Runs an enumeration's discovery pass.
     *
     * @param task the enumeration
     * @return what happened
     */
    @PostMapping("/tasks/{task}/discover")
    @AdminAccess(ReconciliationAdminOperations.OPERATE)
    public Map<String, Object> discover(@PathVariable @AdminObject String task) {
        return service.discover(task);
    }

    /**
     * Requeues an enumeration's quarantined members.
     *
     * @param task the enumeration
     * @return how many were requeued
     */
    @PostMapping("/tasks/{task}/members/requeue")
    @AdminAccess(ReconciliationAdminOperations.OPERATE)
    public Map<String, Object> requeueMembers(@PathVariable @AdminObject String task) {
        return service.requeueMembers(task);
    }

    /**
     * Walks one scope now.
     *
     * @param task    the enumeration
     * @param scope   the scope key
     * @param request {@code {"kind":"full"}} to force a full walk; may be empty
     * @return what happened
     */
    @PostMapping("/tasks/{task}/scopes/{scope}/walk")
    @AdminAccess(ReconciliationAdminOperations.OPERATE)
    public Map<String, Object> walk(@PathVariable @AdminObject String task, @PathVariable String scope,
                                    @RequestBody(required = false) WalkRequest request) {
        return service.scopeAction(task, scope, "walk", request == null ? null : request.kind());
    }

    /**
     * Says what a walk of one scope would do, and writes nothing.
     *
     * @param task  the enumeration
     * @param scope the scope key
     * @return the preview
     */
    @PostMapping("/tasks/{task}/scopes/{scope}/preview")
    @AdminAccess(ReconciliationAdminOperations.OPERATE)
    public Map<String, Object> preview(@PathVariable @AdminObject String task, @PathVariable String scope) {
        return service.scopeAction(task, scope, "preview", null);
    }

    /**
     * Clears one scope's walk state, keeping its members.
     *
     * @param task  the enumeration
     * @param scope the scope key
     * @return what was reset
     */
    @PostMapping("/tasks/{task}/scopes/{scope}/reset")
    @AdminAccess(ReconciliationAdminOperations.OPERATE)
    public Map<String, Object> resetScope(@PathVariable @AdminObject String task, @PathVariable String scope) {
        return service.scopeAction(task, scope, "reset-scope", null);
    }

    /**
     * Drops a withheld sweep without reporting its members gone.
     *
     * @param task  the enumeration
     * @param scope the scope key
     * @return what was discarded
     */
    @PostMapping("/tasks/{task}/scopes/{scope}/sweep/discard")
    @AdminAccess(ReconciliationAdminOperations.OPERATE)
    public Map<String, Object> discardSweep(@PathVariable @AdminObject String task, @PathVariable String scope) {
        return service.scopeAction(task, scope, "discard-sweep", null);
    }

    /**
     * Confirms a withheld sweep, reporting its members gone.
     *
     * @param task  the enumeration
     * @param scope the scope key
     * @return what was reported
     */
    @PostMapping("/tasks/{task}/scopes/{scope}/sweep/confirm")
    @AdminAccess(ReconciliationAdminOperations.PRIVILEGED)
    public Map<String, Object> confirmSweep(@PathVariable @AdminObject String task, @PathVariable String scope) {
        return service.scopeAction(task, scope, "confirm-sweep", null);
    }

    private static ResponseEntity<RunView> accepted(RunSnapshot run) {
        String href = runHref(run);
        RunView view = RunViews.toView(run, href);
        return OperationResponses.accepted(view.operation(), view, URI.create(href), OperationLocationHeader.LOCATION);
    }

    private static String runHref(RunSnapshot run) {
        return TASKS + run.task() + "/runs/" + run.id();
    }

    /**
     * What a run request may say.
     *
     * @param tier   {@code hot} or {@code cold}; hot when absent
     * @param dryRun fetch and compare without applying
     */
    public record RunRequest(String tier, boolean dryRun) {
    }

    /**
     * The keys a backfill fetches.
     *
     * @param keys correlation keys, in the form the task's key codec writes them
     */
    public record BackfillRequest(List<String> keys) {
    }

    /**
     * Which tier a cursor reset applies to.
     *
     * @param tier {@code hot} or {@code cold}; hot when absent
     */
    public record TierRequest(String tier) {
    }

    /**
     * What kind of walk to force.
     *
     * @param kind {@code full}, or absent to let the planner choose
     */
    public record WalkRequest(String kind) {
    }
}
