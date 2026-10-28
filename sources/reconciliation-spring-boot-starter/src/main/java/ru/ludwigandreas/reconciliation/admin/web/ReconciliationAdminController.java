package ru.ludwigandreas.reconciliation.admin.web;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.reconciliation.admin.ReconciliationAdminOperations;
import ru.ludwigandreas.reconciliation.admin.service.ReconciliationAdminService;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.webcore.operation.OperationLocationHeader;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationResponses;
import ru.ludwigandreas.webcore.operation.OperationResult;
import ru.ludwigandreas.webcore.operation.OperationStatus;

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
    public Map<String, Object> task(@PathVariable String task) {
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
    public Map<String, Object> scope(@PathVariable String task, @PathVariable String scope) {
        return service.scope(task, scope);
    }

    /**
     * Runs a task's pass now, or dry-runs it; answers when it has finished.
     *
     * @param task    the task or enumeration
     * @param request the tier and whether to dry-run; may be empty
     * @return {@code 202} with the finished operation and the task as its status resource
     */
    @PostMapping("/tasks/{task}/runs")
    @AdminAccess(ReconciliationAdminOperations.OPERATE)
    public ResponseEntity<Map<String, Object>> run(@PathVariable String task,
                                                   @RequestBody(required = false) RunRequest request) {
        RunRequest run = request == null ? new RunRequest(null, false) : request;
        return finished(task, service.run(task, run.tier(), run.dryRun()));
    }

    /**
     * Fetches an explicit set of keys.
     *
     * @param task    the task
     * @param request the keys
     * @return {@code 202} with the finished operation
     */
    @PostMapping("/tasks/{task}/backfills")
    @AdminAccess(ReconciliationAdminOperations.OPERATE)
    public ResponseEntity<Map<String, Object>> backfill(@PathVariable String task,
                                                        @RequestBody BackfillRequest request) {
        return finished(task, service.backfill(task, request.keys()));
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
    public Map<String, Object> resetCursor(@PathVariable String task,
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
    public Map<String, Object> requeueQuarantined(@PathVariable String task) {
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
    public Map<String, Object> requeueJob(@PathVariable String task, @PathVariable UUID jobId) {
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
    public Map<String, Object> cancelJob(@PathVariable String task, @PathVariable UUID jobId) {
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
    public Map<String, Object> discover(@PathVariable String task) {
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
    public Map<String, Object> requeueMembers(@PathVariable String task) {
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
    public Map<String, Object> walk(@PathVariable String task, @PathVariable String scope,
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
    public Map<String, Object> preview(@PathVariable String task, @PathVariable String scope) {
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
    public Map<String, Object> resetScope(@PathVariable String task, @PathVariable String scope) {
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
    public Map<String, Object> discardSweep(@PathVariable String task, @PathVariable String scope) {
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
    public Map<String, Object> confirmSweep(@PathVariable String task, @PathVariable String scope) {
        return service.scopeAction(task, scope, "confirm-sweep", null);
    }

    /**
     * The run is synchronous - the pass has finished when the service returns - so the envelope is terminal; the
     * contract permits a terminal envelope on a {@code 202}. The task is the status resource: there is no per-run
     * row to point at.
     */
    private static ResponseEntity<Map<String, Object>> finished(String task, Map<String, Object> result) {
        Instant now = Instant.now();
        URI status = URI.create(TASKS + task);
        OperationResponse operation = OperationResponse.builder()
                .id(UUID.randomUUID().toString())
                .status(OperationStatus.SUCCEEDED)
                .submittedAt(now)
                .startedAt(now)
                .finishedAt(now)
                .result(OperationResult.at(status.toString()))
                .build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", operation);
        body.put("result", result);
        return OperationResponses.accepted(operation, body, status, OperationLocationHeader.LOCATION);
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
