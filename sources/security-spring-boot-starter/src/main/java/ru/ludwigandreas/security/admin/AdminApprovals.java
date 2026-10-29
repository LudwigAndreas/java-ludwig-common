package ru.ludwigandreas.security.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.webcore.operation.OperationFailure;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationResult;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * Four-eyes approval of configured privileged operations: holding a call, and listing, deciding and executing it.
 *
 * <p>No handler knows approval exists. The surface guard hands a call needing approval to {@link #hold}, which
 * records it and answers {@code 202}; {@link #approve} later runs the stored request through the same handler as the
 * approver, while {@link AdminAudit} names both people on the operation's own audit event.
 */
public class AdminApprovals {

    /** Where approvals are served. */
    public static final String PATH = "/admin/v1/approvals";

    /** How long an approval may sit in {@code APPROVING} before the sweep calls it interrupted. */
    public static final Duration INTERRUPTED_AFTER = Duration.ofMinutes(5);

    private static final String RESOURCE = "admin-approval";
    private static final String FAILED_CODE = "ludwig.admin.approval.execution-failed";
    private static final String INTERRUPTED_CODE = "ludwig.admin.error.approval-interrupted";

    private final AdminApprovalPolicy policy;
    private final AdminApprovalStore store;
    private final AdminGrants grants;
    private final AuthorityLookup authorities;
    private final AdminAudit audit;
    private final AdminApprovalExecutor executor;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param policy      which operations require approval
     * @param store       where approvals are kept, or null when no store exists (nothing then requires approval)
     * @param grants      who holds which operation
     * @param authorities the live authority lookup, to re-check the requester at approval
     * @param audit       the administrative audit
     * @param executor    runs an approved request
     * @param json        writes the {@code 202} the guard answers with
     * @param clock       the time approvals are measured against
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - each collaborator is a distinct bean the auto-configuration supplies.
    @SuppressWarnings("checkstyle:ParameterNumber")
    AdminApprovals(AdminApprovalPolicy policy, AdminApprovalStore store, AdminGrants grants,
                   AuthorityLookup authorities, AdminAudit audit, AdminApprovalExecutor executor, ObjectMapper json,
                   Clock clock) {
        this.policy = policy;
        this.store = store;
        this.grants = grants;
        this.authorities = authorities;
        this.audit = audit;
        this.executor = executor;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Creates the service over the application's web context.
     *
     * @param policy      which operations require approval
     * @param store       where approvals are kept, or null
     * @param grants      who holds which operation
     * @param authorities the live authority lookup
     * @param audit       the administrative audit
     * @param context     the web application context approved requests are dispatched through
     * @param json        writes the guard's {@code 202}
     * @param clock       the time approvals are measured against
     * @return the service
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - as above.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static AdminApprovals create(AdminApprovalPolicy policy, AdminApprovalStore store, AdminGrants grants,
                                        AuthorityLookup authorities, AdminAudit audit,
                                        org.springframework.web.context.WebApplicationContext context,
                                        ObjectMapper json, Clock clock) {
        return new AdminApprovals(policy, store, grants, authorities, audit, new AdminApprovalExecutor(context), json,
                clock);
    }

    boolean requires(AdminOperation operation) {
        return store != null && policy.requires(operation.id());
    }

    /**
     * Records a call needing approval instead of executing it, and answers {@code 202} with the approval.
     */
    void hold(HttpServletRequest request, HttpServletResponse response, AdminOperation operation,
              LudwigPrincipal requester, String reason) {
        byte[] body;
        try {
            body = request.getInputStream().readNBytes(AdminApproval.MAX_REQUEST_BODY + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (body.length > AdminApproval.MAX_REQUEST_BODY) {
            throw AdminApprovalException.bodyTooLarge(AdminApproval.MAX_REQUEST_BODY);
        }
        Instant now = clock.instant();
        AdminApproval approval = new AdminApproval(UUID.randomUUID(), operation.id(),
                new AdminApproval.StoredRequest(request.getMethod(),
                        request.getRequestURI().substring(request.getContextPath().length()),
                        request.getQueryString(), request.getContentType(),
                        request.getHeader(HttpHeaders.ACCEPT_LANGUAGE), reason, body),
                requester.subject(), requester.type(), now, now.plus(policy.ttl()), AdminApprovalState.PENDING,
                null, null, null);
        store.create(approval);
        recordAs(operation, requester, reason, approval, "requested", AdminOutcome.SUCCEEDED, Map.of());
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        response.setHeader(HttpHeaders.LOCATION, request.getContextPath() + PATH + "/" + approval.id());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        try {
            json.writeValue(response.getOutputStream(), view(approval, false));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Lets the guard admit the dispatch of an approved request, and binds who requested and approved it.
     */
    void admitExecution(UUID id, AdminOperation operation, LudwigPrincipal approver) {
        AdminApproval approval = store == null ? null : store.find(id).orElse(null);
        if (approval == null || approval.state() != AdminApprovalState.APPROVING
                || !approval.operation().equals(operation.id()) || !approver.subject().equals(approval.decidedBy())) {
            throw new AccessDeniedException("Not an approved administrative execution");
        }
        AdminApprovalContext.bind(new AdminApprovalContext(approval.requestedBy(), approval.decidedBy()));
    }

    /**
     * Approvals of the operations the caller holds, newest first.
     *
     * @param caller the caller
     * @param state  only this state, or null
     * @param cursor the previous page's cursor, or null
     * @param size   the page size, or null
     * @return the page, without request bodies
     */
    public AdminPage<ApprovalView> list(LudwigPrincipal caller, AdminApprovalState state, String cursor,
                                        Integer size) {
        List<String> held = grants.heldBy(caller).stream().map(AdminOperation::id).toList();
        if (store == null || held.isEmpty()) {
            return AdminPage.of(List.of(), AdminCursor.pageSize(size), view -> "");
        }
        int pageSize = AdminCursor.pageSize(size);
        Instant afterRequestedAt = null;
        UUID afterId = null;
        if (cursor != null) {
            List<String> position = AdminCursor.decode(cursor, 2);
            afterRequestedAt = Instant.parse(position.get(0));
            afterId = UUID.fromString(position.get(1));
        }
        List<ApprovalView> rows = store.list(held, state, afterRequestedAt, afterId, pageSize + 1).stream()
                .map(approval -> view(approval, false)).toList();
        return AdminPage.of(rows, pageSize,
                row -> AdminCursor.encode(row.requestedAt().toString(), row.id().toString()));
    }

    /**
     * One approval; its stored body only to a caller who may decide it, while it is pending.
     *
     * @param caller the caller
     * @param id     the approval id
     * @return the approval
     */
    public ApprovalView get(LudwigPrincipal caller, UUID id) {
        AdminApproval approval = visible(caller, id);
        return view(approval, !caller.subject().equals(approval.requestedBy()));
    }

    /**
     * Approves a pending call and executes it, as the approver.
     *
     * @param approver the caller
     * @param id       the approval id
     * @param request  the approver's request, which the stored one is replayed over
     * @param response the approver's response, untouched by the execution
     * @return the approval, with the execution's outcome
     */
    public ApprovalView approve(LudwigPrincipal approver, UUID id, HttpServletRequest request,
                                HttpServletResponse response) {
        AdminApproval approval = decidable(approver, id);
        PrincipalRef requester = new PrincipalRef(approval.requesterType(), approval.requestedBy());
        LudwigPrincipal requesterNow = LudwigPrincipal.builder().subject(approval.requestedBy())
                .type(approval.requesterType()).roles(authorities.lookup(requester).roles()).build();
        if (!grants.holds(requesterNow, approval.operation())) {
            throw AdminApprovalException.requesterLostOperation(approval.requestedBy(), approval.operation());
        }
        Instant now = clock.instant();
        if (!store.transition(id, AdminApprovalState.PENDING, AdminApprovalState.APPROVING, approver.subject(), now,
                null)) {
            throw AdminApprovalException.notPending(id, current(id).state());
        }
        AdminApproval.Outcome outcome = executor.execute(current(id), request, response);
        store.transition(id, AdminApprovalState.APPROVING, AdminApprovalState.EXECUTED, null, clock.instant(),
                outcome);
        AdminApproval executed = current(id);
        recordAs(operation(approval), approver, null, executed, "approved",
                AdminOutcome.SUCCEEDED, Map.of("status", outcome.status()));
        return view(executed, false);
    }

    /**
     * Rejects a pending call.
     *
     * @param decider the caller
     * @param id      the approval id
     * @return the approval
     */
    public ApprovalView reject(LudwigPrincipal decider, UUID id) {
        AdminApproval approval = decidable(decider, id);
        return close(decider, approval, AdminApprovalState.REJECTED, "rejected");
    }

    /**
     * Withdraws one's own pending call.
     *
     * @param requester the caller
     * @param id        the approval id
     * @return the approval
     */
    public ApprovalView withdraw(LudwigPrincipal requester, UUID id) {
        AdminApproval approval = visibleToRequesterOrHolder(requester, id);
        if (!requester.subject().equals(approval.requestedBy())) {
            throw AdminApprovalException.notRequester();
        }
        pendingOrExpire(approval);
        return close(requester, approval, AdminApprovalState.WITHDRAWN, "withdrawn");
    }

    /**
     * Expires undecided approvals and marks as interrupted those left executing by a replica that stopped; audits
     * each. Run on a schedule by the store's module.
     *
     * @return how many approvals moved
     */
    public int sweep() {
        if (store == null) {
            return 0;
        }
        Instant now = clock.instant();
        List<AdminApproval> expired = store.expire(now);
        expired.forEach(approval -> audit.recordSystem(approval.operation(), RESOURCE, approval.id().toString(),
                AdminOutcome.REFUSED_BY_STATE, Map.of("approval", "expired", "requestedBy", approval.requestedBy())));
        List<AdminApproval> interrupted = store.interrupt(now.minus(INTERRUPTED_AFTER));
        interrupted.forEach(approval -> audit.recordSystem(approval.operation(), RESOURCE, approval.id().toString(),
                AdminOutcome.FAILED, Map.of("approval", "interrupted", "requestedBy", approval.requestedBy(),
                        "approvedBy", String.valueOf(approval.decidedBy()))));
        return expired.size() + interrupted.size();
    }

    private ApprovalView close(LudwigPrincipal caller, AdminApproval approval, AdminApprovalState to,
                               String action) {
        if (!store.transition(approval.id(), AdminApprovalState.PENDING, to, caller.subject(), clock.instant(),
                null)) {
            throw AdminApprovalException.notPending(approval.id(), current(approval.id()).state());
        }
        AdminApproval closed = current(approval.id());
        recordAs(operation(approval), caller, null, closed, action, AdminOutcome.SUCCEEDED, Map.of());
        return view(closed, false);
    }

    /** Visible, decidable by this caller (not the requester), and still pending. */
    private AdminApproval decidable(LudwigPrincipal caller, UUID id) {
        AdminApproval approval = visible(caller, id);
        if (caller.subject().equals(approval.requestedBy())) {
            throw AdminApprovalException.ownRequest();
        }
        pendingOrExpire(approval);
        return approval;
    }

    private void pendingOrExpire(AdminApproval approval) {
        if (approval.state() != AdminApprovalState.PENDING) {
            throw AdminApprovalException.notPending(approval.id(), approval.state());
        }
        Instant now = clock.instant();
        if (!approval.expiresAt().isAfter(now)) {
            if (store.transition(approval.id(), AdminApprovalState.PENDING, AdminApprovalState.EXPIRED, null, now,
                    null)) {
                audit.recordSystem(approval.operation(), RESOURCE, approval.id().toString(),
                        AdminOutcome.REFUSED_BY_STATE,
                        Map.of("approval", "expired", "requestedBy", approval.requestedBy()));
            }
            throw AdminApprovalException.notPending(approval.id(), AdminApprovalState.EXPIRED);
        }
    }

    /** An approval exists for this caller only if they hold its operation; otherwise it is not found. */
    private AdminApproval visible(LudwigPrincipal caller, UUID id) {
        AdminApproval approval = store == null ? null : store.find(id).orElse(null);
        if (approval == null || !grants.holds(caller, approval.operation())) {
            throw AdminApprovalException.notFound(id);
        }
        return approval;
    }

    private AdminApproval visibleToRequesterOrHolder(LudwigPrincipal caller, UUID id) {
        AdminApproval approval = store == null ? null : store.find(id).orElse(null);
        if (approval == null || !caller.subject().equals(approval.requestedBy())
                && !grants.holds(caller, approval.operation())) {
            throw AdminApprovalException.notFound(id);
        }
        return approval;
    }

    private AdminApproval current(UUID id) {
        return store.find(id).orElseThrow(() -> AdminApprovalException.notFound(id));
    }

    private AdminOperation operation(AdminApproval approval) {
        return grants.catalog().find(approval.operation())
                .orElseThrow(() -> new IllegalStateException("Uncatalogued approval operation "
                        + approval.operation()));
    }

    private void recordAs(AdminOperation operation, LudwigPrincipal principal, String reason, AdminApproval approval,
                          String action, AdminOutcome outcome, Map<String, Object> facts) {
        Map<String, Object> attributes = new LinkedHashMap<>(facts);
        attributes.put("approval", action);
        attributes.put("requestedBy", approval.requestedBy());
        try (AdminInvocation.Scope ignored = AdminInvocation.open(new AdminInvocation(operation, principal, reason))) {
            audit.record(RESOURCE, approval.id().toString(), outcome, attributes);
        }
    }

    private ApprovalView view(AdminApproval approval, boolean withBody) {
        Integer status = approval.outcome() == null ? null : approval.outcome().status();
        OperationStatus envelopeStatus = approval.state().envelopeStatus(status);
        String self = PATH + "/" + approval.id();
        OperationResponse.OperationResponseBuilder envelope = OperationResponse.builder()
                .id(approval.id().toString())
                .status(envelopeStatus)
                .submittedAt(approval.requestedAt());
        if (approval.state() != AdminApprovalState.PENDING) {
            envelope.finishedAt(approval.state().isTerminal() ? approval.decidedAt() : null);
        }
        if (envelopeStatus == OperationStatus.SUCCEEDED) {
            envelope.result(OperationResult.at(self));
        } else if (envelopeStatus == OperationStatus.FAILED) {
            envelope.failure(approval.state() == AdminApprovalState.INTERRUPTED
                    ? OperationFailure.of(INTERRUPTED_CODE)
                    : OperationFailure.of(FAILED_CODE, List.of(String.valueOf(status))));
        }
        AdminApproval.StoredRequest request = approval.request();
        boolean showBody = withBody && approval.state() == AdminApprovalState.PENDING && request.body().length > 0;
        return new ApprovalView(approval.id(), approval.operation(), request.method(), request.path(),
                request.query(), request.reason(), showBody ? new String(request.body(), StandardCharsets.UTF_8) : null,
                approval.requestedBy(), approval.requestedAt(), approval.expiresAt(), approval.state(),
                approval.decidedBy(), approval.decidedAt(), approval.outcome() == null ? null
                        : new OutcomeView(approval.outcome().status(), approval.outcome().contentType(),
                                new String(approval.outcome().body(), StandardCharsets.UTF_8)),
                envelope.build());
    }

    /**
     * An approval as its API shows it.
     *
     * @param id          the approval id
     * @param operation   the operation it performs
     * @param method      the stored request's method
     * @param path        its path
     * @param query       its query, or null
     * @param reason      its stated reason
     * @param body        its body, only to a caller who may decide it while pending, else null
     * @param requestedBy the requester
     * @param requestedAt when it was requested
     * @param expiresAt   when it expires undecided
     * @param state       where it stands
     * @param decidedBy   who decided it, or null
     * @param decidedAt   when, or null
     * @param outcome     how the executed request was answered, or null
     * @param operationEnvelope the approval as an operation: PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED or
     *                          EXPIRED
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor, named at its one construction site.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public record ApprovalView(UUID id, String operation, String method, String path, String query, String reason,
                               String body, String requestedBy, Instant requestedAt, Instant expiresAt,
                               AdminApprovalState state, String decidedBy, Instant decidedAt, OutcomeView outcome,
                               OperationResponse operationEnvelope) {
    }

    /**
     * How an executed request was answered.
     *
     * @param status      its HTTP status
     * @param contentType its content type, or null
     * @param body        its body, at most 16 KiB
     */
    public record OutcomeView(int status, String contentType, String body) {
    }
}
