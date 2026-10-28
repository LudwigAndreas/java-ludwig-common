## Context

This crosses `security-spring-boot-starter` (gate, endpoints, SPI) and `job-core` (storage), and it adds a new
capability.

- `AdminSurfaceGuard` (`HandlerInterceptor`) and `AdminAccessEvaluator` decide every `/admin/**` call before the
  handler runs.
- The security starter has no `DataSource` and must not gain one. `job-core` owns Liquibase-managed tables and
  already depends on the security starter.

## Goals / Non-Goals

**Goals:**
- No handler is aware of approval.
- An approved request runs through exactly the same handler, validation, authorization and audit as an unapproved
  one.

**Non-Goals:** see the proposal.

## Decisions

1. **Intercept after authorization, before invocation.** `AdminAccessEvaluator` already resolves the handler's
   operation. When the operation is in `approval.required` and the request is not an approved execution, it records
   the approval through `AdminApprovalStore` and short-circuits with `202`. The body is read once through a
   size-limited `ContentCachingRequestWrapper` installed by the admin filter for `/admin/**` POST, PUT and DELETE
   only, with a 64 KiB cap and `413` above it.

   The reason check runs first, so a privileged request without `Admin-Reason` is still `400` and never becomes an
   approval.
2. **Execution by internal re-dispatch.** On approve, the controller builds an `HttpServletRequestWrapper` over the
   approve request. The wrapper supplies the stored method, URI, query, body, `Content-Type` and `Admin-Reason`, and
   carries a request attribute naming the approval id. It forwards through `RequestDispatcher.forward` to the
   `DispatcherServlet` and captures the response with a `ContentCachingResponseWrapper`.

   The guard sees the attribute, verifies that the approval is `APPROVING` and owned by this dispatch, and lets the
   handler run. `AdminInvocation` carries `approvedBy` and `requestedBy` into `AdminAudit`, which adds them as
   attributes.

   The approver is the authenticated principal, so the handler's own checks (tenant confinement, object scope)
   apply to the approver. The requester's continued authority is re-checked through `AuthorityResolver` for the
   requester's `PrincipalRef` before the forward.

   Alternatives rejected:
   - *Storing a command object per operation.* That needs every handler to be rewritten.
   - *Executing as the requester.* The requester is not present, and their identity cannot be re-authenticated.
3. **State machine.** `PENDING` moves to one of `APPROVING`, `REJECTED`, `WITHDRAWN` or `EXPIRED`; `APPROVING`
   moves to `EXECUTED` with an outcome.

   Each transition is a conditional update (`WHERE state = ?`), so two approvers racing produce one execution;
   the loser gets `409`. If the process dies during `APPROVING`, the approval is marked `FAILED` with code
   `ludwig.admin.error.approval-interrupted` by the expiry sweep after `PT5M`. It is never re-executed, because
   the handler may have committed.

   Envelope mapping:

   | Approval state | Envelope status |
   |---|---|
   | `PENDING` | `PENDING` |
   | `APPROVING` | `RUNNING` |
   | `EXECUTED`, 2xx | `SUCCEEDED` |
   | `EXECUTED`, other | `FAILED` (with the recorded status) |
   | `REJECTED` or `WITHDRAWN` | `CANCELLED` |
   | `EXPIRED` | `EXPIRED` |
4. **Storage SPI.** `AdminApprovalStore` (security, `admin` package) declares `create`, `find`, `list(operations,
   status, cursor)`, `transition(id, from, to, decidedBy, outcome)` and `expireOlderThan(cutoff)`.
   `JdbcAdminApprovalStore` (job-core) implements it on a `job_admin_approval` table, with the stored body as
   `BYTEA` and an index on `(operation, state, created_at)`. The expiry sweep is a `SelfSchedulingLifecycle` under
   the run lock `ludwig-admin-approval-expiry`.

   **Mechanical check:** startup fails if `approval.required` is non-empty and no `AdminApprovalStore` bean exists
   (`AdminStartupChecks`).
5. **Stored bodies.** A privileged request body can hold data the audit envelope would redact, such as a grant's
   values or a PAT owner. Bodies are therefore never returned by the list endpoint. `GET /approvals/{id}` returns
   the body only to callers who may decide the approval, and it is deleted when the approval reaches a terminal
   state (the outcome body is kept, capped at 16 KiB).

**Dependency-direction check.** Does `job-core` gain an in-repo dependency? No. It already depends on
`security-spring-boot-starter`. Does `security-spring-boot-starter` gain one? No. The SPI is declared in it and
implemented downstream. No edge is added.

**POMs.** No POM changes: not the root `pom.xml`, `ludwig-bom` or `ludwig-service-parent`.

## Risks / Trade-offs

- [The forward re-dispatch runs filters a second time.] → `OncePerRequestFilter`s skip a forward by default.
  The test asserts single audit and single metric counts.
- [A handler reads headers other than `Admin-Reason`.] → The wrapper exposes only the stored allow-list
  (`Content-Type`, `Admin-Reason`, `Accept-Language`). Any handler depending on another header is found by the
  service ITs.
- [An approval is used to get around tenant confinement.] → Confinement applies to the approver at execution. An
  approver from another tenant gets the same `404` they would get acting alone.
