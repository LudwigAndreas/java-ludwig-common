# Analyst guide

For system analysts who specify services, integrations and reports on this platform. It describes what the
platform offers as behaviour and contracts, and where the authoritative definition of each one lives.
Implementation details are in the module READMEs; the binding behavioural specifications are in
`openspec/specs/`.

## 1. Capability map

| Need in a specification | Platform capability | Reference |
|---|---|---|
| CRUD over a business entity with search, paging and sorting | REST + OData query (`$filter`, `$orderby`, `$top`, `$skip`) with a per-entity field allowlist | [odata-filter](../sources/odata-filter-spring-boot-starter/README.md), spec `odata-query-contract` |
| Concurrent edits must not overwrite each other | optimistic locking: every update carries `version`; a stale one gets `409` | [db-core](../sources/db-core/README.md), [crud-service-example](../services/crud-service-example/README.md) |
| Who may see or change which rows | roles per service plus data scopes (owner, tenant, partner, custom dimensions) set in configuration | [security](../sources/security-spring-boot-starter/README.md), [data-scope-configuration.md](data-scope-configuration.md) |
| Partners calling the API | mTLS partner identity, partner-scoped data | security, identity-projection |
| Machine or script access on behalf of a person | personal access tokens with attenuated scopes | [pat-spring-boot-starter](../sources/pat-spring-boot-starter/README.md), spec `personal-access-token` |
| Something happened and other systems must learn of it | domain event through the transactional outbox, delivered at least once, ordered per key | [outbox](../sources/outbox-spring-boot-starter/README.md), spec `messaging-envelope` |
| React to another system's events | Kafka consumer with retries, dead-letter topic and dedup | [messaging](../sources/messaging-spring-boot-starter/README.md) |
| A request must not be executed twice | `Idempotency-Key` header; a retried request gets the original response | [idempotency](../sources/idempotency-spring-boot-starter/README.md), spec `idempotency-claim` |
| An operation takes longer than an HTTP request | long-running operation contract (§4) | spec `long-running-operations` |
| Business report or data export | XLSX/CSV reports, saved configurations, scheduled subscriptions | [export](../sources/export-spring-boot-starter/README.md) |
| Users upload a spreadsheet to create or change data | file action: upload, validate, confirm, commit, reject report | [file-action](../sources/file-action-spring-boot-starter/README.md), spec `interactive-file-action` |
| A partner delivers a daily bulk file | file ingest with arrival detection, quarantine, balance check | [file-ingest](../sources/file-ingest-spring-boot-starter/README.md) |
| Keep a local copy of externally owned data in sync | reconciliation tasks with quotas and freshness SLO | [reconciliation](../sources/reconciliation-spring-boot-starter/README.md) |
| Call a partner's REST or gRPC API | named clients with timeouts, retries, circuit breakers | [rest-client](../sources/rest-client-spring-boot-starter/README.md), [grpc-client](../sources/grpc-client-spring-boot-starter/README.md) |
| Notify a person (email, chat, webhook, in-app inbox) | notification-service | [notification-service](../services/notification-service/README.md) |
| Per-user settings, preferences and consents | user-settings, with consent versions recorded | [user-settings](../sources/user-settings-spring-boot-starter/README.md) |
| Who did what, when | the audit trail (§6) | [audit-core](../sources/audit-core/README.md) |
| Messages in the user's language | every message in English and Russian, chosen by `Accept-Language` | [web-core](../sources/web-core-spring-boot-starter/README.md), spec `i18n-bundles` |

## 2. API documents

Each service's interfaces are published as generated documents in [docs/api/](api/README.md):

| Document | Describes |
|---|---|
| `<service>.openapi.yaml` | HTTP endpoints, request and response schemas, the `ProblemDetail` error shape |
| `<service>.asyncapi.yaml` | Kafka topics the service reads and writes, message schemas, envelope headers, dead-letter channels |

They are generated from the running code and the build fails when they drift, so they are always
current. A change to an interface shows up as a diff of these files; review that diff when you review a
change.

## 3. Errors

Every error response is an RFC 9457 `ProblemDetail`:

| Field | Meaning |
|---|---|
| `status` | HTTP status |
| `code` | stable machine-readable code, `ludwig.<module>.error.<reason>`; clients branch on this |
| `title`, `detail` | localized human text; never parse it |
| `traceId` | quote it when reporting a problem |

`401` means no valid credentials; `403` means not allowed, and is also returned for a row that exists
but is outside the caller's data scope, so existence is never revealed.

## 4. Long-running operations

Any operation that may outlast an HTTP request (reports, imports, reconciliation jobs) follows one
contract (`web-core`, spec `long-running-operations`):

```text
PENDING -> RUNNING -> SUCCEEDED | FAILED | CANCELLED
SUCCEEDED -> EXPIRED   (the result was removed by retention; the record stays)
```

| Step | Response |
|---|---|
| submit, queued | `202` with `Location` (or `Operation-Location`) to poll |
| submit, already done | `200` with the finished operation |
| poll | `200` with status, progress, and `Retry-After` while not finished |
| cancel | `202`; cancellation is cooperative; a finished operation returns its final state |

`EXPIRED` and `CANCELLED` are not failures.

## 5. Events

Every event published through the outbox carries the same envelope headers:

| Header | Meaning |
|---|---|
| `ludwig-event-type` | the kind of event |
| `ludwig-event-version` | the payload schema version |
| `ludwig-aggregate-type`, `ludwig-aggregate-id` | what the event is about |
| `ludwig-idempotency-key` | stable dedup key across redeliveries |
| `ludwig-produced-at` | when it was produced (UTC) |
| `ludwig-trace-id`, `X-Correlation-Id` | tracing and correlation |

Guarantees to state in a specification: **at least once** (consumers must tolerate duplicates; the
platform's dedup filter does this), **ordered per aggregate/ordering key**, not globally. A new
payload version is a new `ludwig-event-version`; a consumer refuses a version it does not know rather
than misreading it. Event types per service are in its AsyncAPI document.

## 6. Audit trail

Every state change made by a person or partner, and every security decision a module records, becomes
one `AuditEvent`. With `audit-spring-boot-starter` the events are rows in `audit_event`:

| Column | Meaning |
|---|---|
| `occurred_at` | when it happened |
| `category`, `action` | e.g. `export` / `run.succeeded`; categories are listed in [audit-core](../sources/audit-core/README.md) |
| `actor_subject`, `actor_type`, `on_behalf_of` | who; the same id as the `created_by` column on the changed row |
| `resource_type`, `resource_id`, `resource_name` | what |
| `outcome`, `reason` | `SUCCESS`, `FAILURE`, `DENIED` or `PARTIAL` |
| `correlation_id`, `trace_id` | links to logs and traces of the same request |
| `attributes` | action-specific details (JSON), with sensitive values replaced by `***REDACTED***` |

Typical questions:

```sql
-- everything one person did on a day
SELECT occurred_at, category, action, resource_type, resource_id, outcome
FROM audit_event
WHERE actor_subject = :subject AND occurred_at >= :day AND occurred_at < :day + interval '1 day'
ORDER BY occurred_at;

-- the history of one object
SELECT occurred_at, action, actor_subject, outcome, attributes
FROM audit_event WHERE resource_type = :type AND resource_id = :id ORDER BY occurred_at;
```

The trail is append-only and kept per category (default seven years). Without the audit starter, events
are written to the service log only. Gaps in coverage are listed in
[admin-guide.md §9](admin-guide.md#9-known-operational-gaps).

## 7. Data model conventions

- Entities are built from db-core base classes: a generated id (UUID by default), audit columns
  `created_at`, `created_by`, `updated_at`, `updated_by`, `version` on versioned (editable) entities,
  soft delete on soft-deletable ones, and immutable snapshot entities for audit and history rows.
- Personal data has explicit retention windows per module (for example notification-service scrubs
  addresses and rendered bodies after seven days); a specification that stores personal data must state
  its retention.
- Tables per module are listed in [admin-guide.md §4](admin-guide.md#4-database).

## 8. Behavioural specifications

`openspec/specs/<capability>/spec.md` holds the binding requirements, each with WHEN/THEN scenarios.
They are the source of truth for what a module promises; a change to behaviour starts as an OpenSpec
change proposal (`openspec/changes/`) that a reviewer approves before code is written. Useful starting
points: `long-running-operations`, `messaging-envelope`, `idempotency-claim`, `problem-detail-pipeline`,
`odata-query-contract`, `data-access`, `personal-access-token`, `notification-inbox`,
`platform-announcement`, `interactive-file-action`, `published-api-document`.
