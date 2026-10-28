## Why

The privileged tier covers the administrative actions that cannot be undone or that change access:

- discarding outbox messages;
- onboarding or suspending a partner;
- granting data scope;
- issuing a PAT for another owner;
- force-releasing a lease;
- moving a consumer's offset.

Today one person holding the operation performs them alone, with only a stated reason. Regulated deployments
require a second person to approve such actions before they take effect. This was out of scope in
`admin-authorization` (`openspec/intents/admin-surface-follow-ups.md` §2).

## What Changes

- **Opt-in per operation.** `ludwig.admin.approval.required` lists operation ids, for example
  `["identity.privileged", "outbox.privileged"]`. It is empty by default. An id that is not catalogued, or is not
  privileged, fails startup.
- **A request becomes a pending approval.** A call to an endpoint whose operation requires approval is not executed.
  The surface records the request (method, path, query, body up to 64 KiB, `Admin-Reason`, requester) and answers
  `202`:
  - the body is an `approval` resource;
  - its operation envelope is `PENDING`;
  - `Location` points to `/admin/v1/approvals/{id}`.
- **Approval endpoints.** These are open to any caller the administrative chain admits; visibility and action are
  limited by the operation:
  - `GET /admin/v1/approvals?status=` lists pending and decided approvals for the operations the caller holds;
  - `GET /admin/v1/approvals/{id}` returns one approval;
  - `POST /admin/v1/approvals/{id}/approve` and `.../reject` decide an approval. The caller must hold the operation
    and must not be the requester (by subject). Approving executes the stored request with the approver as the
    authenticated caller, after re-checking that the requester still holds the operation. The approval then records
    the execution's status code and body, and its envelope becomes `SUCCEEDED` or `FAILED`.
  - `POST /admin/v1/approvals/{id}/withdraw` is for the requester only.
- **Expiry.** An approval not decided within `ludwig.admin.approval.ttl` (default `PT24H`) expires (envelope
  `EXPIRED`) and can no longer be approved.
- **Audit.** Request, approval, rejection, withdrawal and expiry are each audited. The execution's own audit event
  carries `requestedBy` and `approvedBy`.
- **Storage.** `security-spring-boot-starter` stays free of a database. It defines an `AdminApprovalStore` SPI, and
  `job-core` provides the JDBC implementation (a `create-approval` changeset, numbered next in job-core's changelog when applied). Configuring
  `approval.required` without a store fails startup.

## Capabilities

### New Capabilities
- `admin-approval`: four-eyes approval of configured privileged operations.

### Modified Capabilities
None. `admin-authorization`'s requirements are unchanged: an operation still authorizes the call, and approval is an
additional, opt-in gate.

## Non-goals

- Approval chains (N of M) or approver groups distinct from operation holders.
- Approval for `read` or `operate` operations.
- Notifying approvers (email, chat). A UI polls the list, and notification can come later.
- Approving controls submitted through `replica-control-broadcast`. Those requests are commands already; adding
  approval there is a follow-up once both exist.
- Changing a stored request before approval. Reject it and resubmit instead.

## Impact

- `security-spring-boot-starter` (library; parent `common`): the gate, endpoints and SPI. It has 16 in-repo
  dependents.
- `job-core` (library; parent `common`): the JDBC store and migration. It has 9 in-repo dependents.
- Shared contracts: `admin-approval` is new; `admin-authorization` is read but not changed.
- No POM changes. `job-core` already depends on `security-spring-boot-starter`.
- READMEs (EN and RU), the admin guide (enabling approval, break-glass), both services' OpenAPI documents, and
  `CHANGELOG` (EN and RU).
