# admin-approval Specification

## Purpose
Lets a deployment require that a second person approve configured privileged administrative operations before they
take effect, with the request, the decision and the execution all on record.

## Requirements
### Requirement: Approval is configured per privileged operation
`ludwig.admin.approval.required` SHALL list operation ids that require approval. An id that is not in the catalog, or
whose tier is not `privileged`, SHALL fail startup naming it. A non-empty list with no approval store SHALL fail
startup. An empty list SHALL change no behaviour.

#### Scenario: A read operation is listed
- **WHEN** `approval.required` contains `outbox.read`
- **THEN** the application fails to start naming `outbox.read`

### Requirement: A call needing approval is recorded, not executed
A call authorized for an operation that requires approval SHALL NOT invoke the handler. It SHALL record the method,
path, query, body (at most 64 KiB, else `413`), `Admin-Reason` and requester, and answer `202` with an approval
resource whose operation envelope is `PENDING` and a `Location` naming `/admin/v1/approvals/{id}`. A call that would
fail authorization or lacks a required reason SHALL be refused as before, with no approval recorded.

#### Scenario: Alice asks to discard a message
- **WHEN** `outbox.privileged` requires approval and alice, holding it, discards message `m-1` with a reason
- **THEN** the response is `202` with a pending approval, `m-1` is unchanged, and an audit event records the request

### Requirement: Only a different holder of the operation can approve or reject
`POST /admin/v1/approvals/{id}/approve` and `.../reject` SHALL require that the caller holds the approval's operation
and is not its requester. Approving SHALL re-check that the requester still holds the operation (`409` if not),
SHALL execute the stored request with the approver as the authenticated caller, and SHALL record the execution's
status code and body on the approval, whose envelope becomes `SUCCEEDED` for a 2xx and `FAILED` otherwise. A decided,
withdrawn or expired approval SHALL answer `409`.

#### Scenario: Bob approves
- **WHEN** bob, holding `outbox.privileged`, approves alice's pending discard
- **THEN** `m-1` becomes `DISCARDED`, the approval's envelope is `SUCCEEDED` with the recorded status `200`, and the
  discard's audit event carries `requestedBy=alice` and `approvedBy=bob`

#### Scenario: Alice approves her own request
- **WHEN** alice approves her own pending request
- **THEN** the response is `403` and the approval stays pending

#### Scenario: The requester lost the operation meanwhile
- **WHEN** alice's role was revoked before bob approves
- **THEN** the response is `409`, nothing is executed, and the approval stays pending until it expires or is
  rejected

### Requirement: Approvals are listable by those who could decide them, and expire
`GET /admin/v1/approvals` SHALL list only approvals whose operation the caller holds, filterable by status. An approval of
an operation the caller does not hold SHALL be answered as not found. The
requester SHALL be able to withdraw a pending approval. An approval undecided after `ludwig.admin.approval.ttl`
(default `PT24H`) SHALL become `EXPIRED` and SHALL NOT be executable. Every request, decision, withdrawal and expiry
SHALL be audited.

#### Scenario: A support user sees no privileged approvals
- **WHEN** a caller holding only `outbox.read` lists approvals
- **THEN** alice's pending `outbox.privileged` approval is not in the list

#### Scenario: Nobody decides in time
- **WHEN** 24 hours pass without a decision
- **THEN** the approval's envelope is `EXPIRED`, and approving it answers `409`
