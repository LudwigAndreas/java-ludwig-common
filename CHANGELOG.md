# Changelog

All notable changes to this platform are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**One changelog for the whole reactor.** Every module shares one `${revision}` and is released
together, so thirty changelogs would be thirty copies of one list. An entry describes what changed
for a *consumer of the platform*, and names the module when that matters.

**Two locales, one heading set.** `CHANGELOG.ru.md` carries the same `##` headings as this file, in
the same order. `-Prelease` fails the build when either file has no heading for the version being
released — the same rule this repository already applies to `README.md` and `README.ru.md`.

**A release is a tag.** The version is computed by GitVersion from the git history and passed to
Maven as `-Drevision=<computed>`; `.mvn/maven.config` is only the fallback for a checkout with no
tags. Writing a version into that file does not cut a release, and adding a heading here does not
either.

## [Unreleased]

### Added

- **Administrative operations over gRPC** (`grpc-admin-operations`). `@AdminAccess` on a method of a
  `@LudwigGrpcService` is enforced with the same grants, caller admission and audit as `/admin/**`: an operation the
  caller does not hold is `PERMISSION_DENIED` (audited), a privileged one needs `admin-reason` metadata (else
  `INVALID_ARGUMENT`), and the handler runs with `AdminInvocation` bound. Startup fails, naming the method, for an
  unknown operation, for one in `ludwig.admin.approval.required` (approval stays HTTP-only), and for an annotated
  method with no administrative surface. `GrpcMetadataKeys.ADMIN_REASON` in grpc-core.
- **Replica controls: administrative actions that reach every replica** (`replica-control-broadcast`). job-core
  keeps a heartbeat per replica and a database channel of commands and per-replica acknowledgements (migrations
  `job-core-0003`, `job-core-0004`; `ludwig.job-core.control.*`, polling every `PT2S`). A module contributes a
  `ReplicaControl`; `POST /admin/v1/replicas/controls/{control}` validates the arguments, authorizes them by the
  control's own operation (privileged needs `Admin-Reason`), targets every replica live at that moment and answers
  `202`; `GET /admin/v1/replicas/commands/{id}` is `SUCCEEDED` when all acknowledged, `FAILED` when any failed or,
  naming them, when a replica stayed silent past `control.ack-timeout`. `GET /admin/v1/replicas` lists the live
  replicas. Controls must be idempotent.
- **The `caches` area and clearing a cache everywhere** (`replica-control-broadcast`). `GET /admin/v1/caches` lists
  the caches with purpose, tiers and TTL; the `cacheevict` control clears one cache on every replica
  (`caches.operate`, or `caches.privileged` with a reason for a `SECURITY` cache). `LudwigCacheRegistry` gains
  `evictAll(name)` and `settings()`.

- **OData `$filter` on administrative listings** (`admin-listing-odata-filter`). The audit trail, outbox messages,
  reconciliation runs, export runs, file-action submissions and PATs filter with `$filter` over a per-entity allow-list
  (`@Filterable`; free text, secrets and personal data are never on it), e.g.
  `$filter=status eq 'DEAD_LETTER' and eventType eq 'OrderCreated'`. The filter is ANDed under tenant confinement, the
  audit search's 30-day default (lifted by a filter on `occurredAt`) and the path's task, so it only narrows. Paging stays
  keyset (`cursor`, `size`). A search's audit event records the filter's paths and operators, never its values.
  db-core's `AdminQueryFilter` is the seam; without the OData starter a listing answers unfiltered and a `$filter` is
  `400` (`ludwig.admin.filter-unavailable`). `ODataQuery` exposes `filterSummary()`.

- **Four-eyes approval of privileged administrative operations** (`admin-four-eyes-approval`).
  `ludwig.admin.approval.required` lists privileged operations (empty by default; an uncatalogued or non-privileged
  id, or a list without a store, fails startup). Such a call is recorded instead of executed - body up to 64 KiB,
  `413` above - and answered `202` with a pending approval at `/admin/v1/approvals/{id}`. `GET /admin/v1/approvals`
  lists the approvals of the operations the caller holds; a different holder approves (`POST .../approve`, the
  requester's authority re-checked, the stored request then run through the same handler as the approver), rejects or
  lets it expire after `ludwig.admin.approval.ttl` (`PT24H`); the requester may withdraw. Self-approval is `403`, a
  decided approval `409`. Every step is audited and the executed operation's event carries `requestedBy` and
  `approvedBy`. job-core provides the store (`job_admin_approval`, migration `job-core-0002`) and the
  `ludwig-admin-approval-expiry` sweep.

- **Per-object scope for administrative operations** (`admin-object-scope`). An operation on a named object can be
  narrowed to the objects a caller is granted: resource `admin.<area>`, action = tier, built-in dimension `object`.
  Opt-in per area and tier with a `ludwig.security.data.policies."admin.<area>"` entry whose roles are `ALL` or
  `NONE`, and objects granted per subject through `POST /admin/v1/identity/grants`. Out of scope is `403` on
  operate/privileged and, on a read, the area's own not-found `404`; both are audited. Handlers mark the object with
  `@AdminObject` on a `@PathVariable` (ArchUnit `admin.admin-object-is-a-path-variable`); listings filter through
  `AdminObjectScope`. Reconciliation is the first adopter: every `{task}` endpoint and the task listing. Without a
  policy entry nothing changes.

- **Acting party on the audit trail** (`admin-delegation-claim`). A token's RFC 8693 delegation claim
  (`ludwig.security.jwt.actor-claim`, default `act`, read as `act.sub`) names the party a request came through, for
  example an administrative UI. It is recorded as the audit actor's new `via` (column `audit_event.actor_via`,
  migration `0006`) and searchable with `GET /admin/v1/audit/events?$filter=actorVia eq 'admin-console'`; it never grants a role.
  `ludwig.admin.human-callers.required-via` optionally requires people to reach `/admin/**` only through the listed
  parties (`403`, audited, otherwise); service principals are unaffected, so a break-glass service account keeps
  working.
- **`notification-service`: withdrawing and rescheduling announcements** (`add-announcement-withdrawal-and-window-edits`).
  `POST /api/v1/announcements/{id}/withdrawal` (optional reason, idempotent) hides a published announcement from
  every recipient at once and cancels a running email fan-out; emails already sent are not recalled, and the row is
  kept for retention (migration `0024`). `PUT /api/v1/announcements/{id}/window` moves the visibility window: the end
  either way but not into the past, the start only before the window opens, within the maximum length. A withdrawn
  announcement is final. Both are audited, a window change with its old and new values.

- **Database-log consumer controls** (`messaging-db-consumer-controls`). An operator can pause and resume one consumer
  group on one topic on every replica (`POST /admin/v1/messaging/consumers/{group}/topics/{topic}/pause`, `.../resume`,
  `messaging.operate`), and move a paused group's position (`.../offsets`, `messaging.privileged`, `Admin-Reason`
  required) to `earliest`, `latest`, an offset or a timestamp, on one partition or all. A move is `409` unless the
  group is paused, is clamped to the retained log, and answers and audits each partition's old and new position and
  the records skipped or replayed. The pause is a row in the new `consumer_pause` table (migration `0005`) that every
  replica's claim reads, so nothing is broadcast. Only the service's own listener groups can be controlled (`404`
  otherwise). `GET /admin/v1/messaging/consumers` also shows whether each pair is paused, by whom and since when.

- **Reconciliation run history** (`reconciliation-run-history`). Every run that takes its lease - scheduled, manual,
  dry run or backfill - is recorded in `sync_run` (migration `0015`). A record holds:
  - kind, trigger, replica and correlation id;
  - timestamps and outcome;
  - counts: keys demanded, records fetched, rows produced.

  `GET /admin/v1/reconciliation/tasks/{task}/runs` (keyset-paged, filterable by tier and status) and `.../runs/{runId}`
  serve them as the platform operation envelope, under `reconciliation.read`. Runs are kept for
  `ludwig.reconciliation.runs.retention` (`30d`) and purged by `ludwig-reconciliation-run-purge`, which also ends a run
  whose replica died as `FAILED` with `run-abandoned`.
- **Outbox retention** (`outbox-retention-purge`). A run-locked `job-core` job, `ludwig-outbox-retention-purge`,
  deletes the following messages, with their `outbox_status_history` rows, in committed batches of 500:
  - `PUBLISHED` messages, 7 days after publication (`ludwig.outbox.retention.published`, at least `1d`);
  - `DEAD_LETTER` and `DISCARDED` messages, 30 days after they became terminal (`retention.terminal`).

  It never deletes `PENDING`, `PROCESSING` or `FAILED` messages, and an administrator's retry takes a message out of
  retention. Each batch is audited as `outbox.purged` and counted in `ludwig.outbox.purged{status}`. The job is
  listed in `GET /admin/v1/jobs`. Migration `0006` adds `outbox_message.finished_at`; rows that were dead-lettered
  or discarded before it count their retention from the migration.
- **Administrative surface** (`admin-authorization`, `starter-admin-endpoints`). Every module's operator controls
  live under `/admin/v1/<area>/`, authorized by operations of the form `<area>.<tier>`, where the tier is `read`,
  `operate` or `privileged`. A service grants operations to its own roles in `ludwig.admin.grants`, for example
  `"[outbox.operate]": [NOTIFICATION_ADMIN]`; a starter never names a role, and roles never come from token
  claims. The surface fails closed:
  - an ungranted operation is refused;
  - a grant or handler naming an uncatalogued operation fails startup;
  - partners and PAT-credentialed callers are refused.

  A `privileged` call needs an `Admin-Reason` header. Every change and every denial is audited. Human callers are
  confined to their tenant, and `GET /admin/v1/me/operations` lists the caller's operations.

  The new areas are:
  - `identity`: partners, data-scope grants, effective authorities;
  - `audit`: trail search, tenant-confined, with the new `actor_tenant` column;
  - `reconciliation`, `fileingest`, `outbox` (browse, retry, bulk retry, discard), `jobs` (leases, run now, forced
    release), `messaging` (consumer lag), `exports`, `fileactions`, `settings` and `pat`.

  `notification-service` grants them to `NOTIFICATION_ADMIN`, plus the read tier to `NOTIFICATION_SUPPORT`;
  `crud-service-example` grants them to `CATALOG_ADMIN`.
- **AsyncAPI documents** (`add-published-async-document`). New `asyncapi-spring-boot-starter` (Springwolf
  1.9.0) documents a service's Kafka surface: its consumers with their cluster and dead-letter channel,
  every outbox event type it publishes over Kafka, the platform envelope headers, and its clusters as
  servers. Each service commits `docs/api/<service>.asyncapi.yaml`, drift-checked like the OpenAPI one.
  `scripts/api-docs.sh refresh | check | publish --version <v>` refreshes, checks and uploads both kinds to
  Nexus as `ru.ludwigandreas:<service>:<v>:yaml:openapi|asyncapi`; `gate.sh` runs its `missing` check.
- **Declared outbox event types.** An `OutboxEventTypes` bean maps an event type to its payload class (and,
  with `routed`, its route). `ludwig.outbox.events.require-declared=true`, meant for tests, refuses an
  undeclared type, a payload of the wrong class or a different route. audit, export, file-ingest and
  user-settings declare their own events. messaging gains `ListenerContainerFactoryBuilder.consumerNameOf`;
  test-support gains `PublishedDocument`, the shared document check.
- **Named Kafka clusters.** `ludwig.kafka.clusters.<name>.*` declares a cluster with `spring.kafka.*`'s
  shape, bound on top of it; `spring.kafka.*` stays the cluster `default`. A consumer reads from
  `ludwig.messaging.consumers.<name>.cluster` and dead-letters to `dead-letter.cluster` (`source` by
  default). An outbox route writes to the cluster its transport names, `kafka:<cluster>`. Dedup claims on
  a named cluster are scoped `kafka:<cluster>/<topic>`, so the same topic on two clusters never shares a
  claim; `default` keeps its scope. Every platform producer now comes from messaging's
  `KafkaClusterProducers`, and the new `kafka.producers-come-from-the-cluster-seam` architecture rule keeps
  it that way.
- **`messaging-db-spring-boot-starter`: a removable stand-in for the Kafka clusters you own but do not
  have yet.** With the dependency present, every platform write to a cluster named in
  `ludwig.messaging.db.clusters` (default `internal`) - outbox events over `kafka:<cluster>` and dead
  letters addressed to it - is appended to a Kafka-shaped log in the shared PostgreSQL database, and
  `@KafkaListener` consumers on those clusters read it. The log keeps partitions, per-key ordering, one
  position per consumer group, and messaging's retry, dead-letter, version-gate, dedup and interceptor
  behaviour. Every other cluster is untouched. No listener, factory bean or outbox route changes.
  `ludwig.messaging.db.mode=draining` moves the replaced clusters' writes to their own addresses while the
  log drains, and
  `ludwig.messaging.db.lag` reports when the dependency can be removed. A new
  `kafka.nothing-depends-on-the-db-log-transport` architecture rule keeps it removable.
- **gRPC between platform services.** `grpc-core`, `grpc-server-spring-boot-starter` and
  `grpc-client-spring-boot-starter`: services serve and call each other over gRPC with the platform's
  principal, correlation id, caller preferences, problem mapping, deadlines, resilience, idempotent
  replay and audit carried across the transport. Contracts are `<service>-grpc-api` modules parented
  by the new **`ludwig-grpc-api-parent`**, so a contract's POM is that parent and an `artifactId`;
  `scripts/manifest.sh layout` fails one that is incompletely declared. MapStruct maps protobuf
  messages in services (`@Mapper(config = ProtobufMapperConfig.class)`), and `crud-service-example`
  serves `GetProduct`/`GetProducts` from the new `catalog-grpc-api` as the worked example.
- **`outbound-token-core`**: the one machine-token cache, client-credentials mint and relay-token
  reader, shared by `rest-client-spring-boot-starter` and `grpc-client-spring-boot-starter`.

- **`test-support-local`: run a service locally with its backing services, partner stubs and seed
  data.** A new test-scoped module. A service's `Local<Name>Application`, in `src/test`, starts the
  service's own `main` through `LocalRun` with the PostgreSQL, Kafka, LocalStack and Redis it names, one
  HTTP stand-in per `@LudwigRestClient` name (`PartnerStub`, wired by setting that client's `base-url`
  ahead of every other property source), and ordered `SeedFixture`s with `reset` / `keep` / `none`
  modes. A stub can be made `unavailable`, `slow`, `reset`, `empty` or `refused` from test code, a
  start-up property, or WireMock's admin API while the service is paused at a breakpoint. The same
  stubs serve an integration test through `@WithPartnerStubs`. `KafkaDeliveries` delivers one record
  more than once. The API is provisional until a service adopts it.
- **`notification-service` runs whole on a developer machine.** `LocalNotificationApplication`, in the
  service's test sources, starts the service's own `main` with PostgreSQL, Kafka, a mail server,
  stand-ins for the identity provider, the chat API and a webhook receiver, and seed data; every channel
  and both ingresses are on. Tokens are real - signed by the stand-in issuer and validated by the
  production decoder - and the start-up summary prints a ready `curl` per seeded caller. Start it with
  `mvn -pl :notification-service spring-boot:test-run
  -Dspring-boot.run.main-class=ru.ludwigandreas.notification.local.LocalNotificationApplication`. The
  service README's "Running locally" is the reference example of `test-support-local`.
- **`test-support-local`: partners that are not rest clients, and mail.** A stub publishes its address
  where `PartnerStub.address()` says - a rest client's `base-url` (the default), any property
  (`StubAddress.property`), or nowhere (`StubAddress.none`, for a partner whose address is data).
  `LocalRun.mail()` starts an in-process SMTP and IMAP server, readable through `LocalMailbox`. A
  `SummaryContributor` bean and `PartnerStub.summaryLines` add to the start-up summary.

### Changed

- **security-spring-boot-starter: one administrative evaluation for every transport** (`grpc-admin-operations`).
  `AdminAccessEvaluator.authorize(Authentication, LudwigPrincipal, AdminOperation, String reason, Object target)`
  returns an `AdminInvocation.Scope`, for HTTP and gRPC alike. Partner, personal-access-token and
  acting-party admission moved from `AdminSurfaceGuard` to the new `AdminCallerPolicy` bean (the guard gains a
  constructor taking it). On HTTP a privileged call's missing reason is now reported before an object-scope refusal.
  `RuleGroup.GRPC` names `@AdminAccess` as honoured on gRPC services.
- **BREAKING (Java API) - `LudwigCacheRegistry` gains `evictAll(String)` and `settings()`**
  (`replica-control-broadcast`). A custom registry implementation must add both; `DefaultLudwigCacheRegistry` does.

- **BREAKING - administrative listings take `$filter` instead of their filter parameters**
  (`admin-listing-odata-filter`). Removed: the audit search's `category`, `action`, `actor`, `via`, `resourceType`,
  `resourceId`, `outcome`, `from`, `to`; the outbox's `status`, `eventType`, `destination`, `createdBefore`; the run
  history's `tier`, `status`; the export runs' `requester`, `report`, `status`; the submissions' `action`,
  `submitter`, `status`; the PAT listing's `owner`, `status`. A removed parameter is ignored if sent. Status filters
  name stored values: `outcome eq 'FAILURE'` on a run, `state eq 'CANCELLED'` on a submission, `revokedAt eq null` on a
  token. `$orderby`, `$top`, `$skip` and `$count` under `/admin/**` are `400`. Java: the listings' services,
  repositories and auto-configuration bean methods take the parsed filter; `PatAdminService.TokenStatus` and
  `AuditTrailSearch.Filter` are removed; `AuditTrailQuery` loses `via`; `ODataQuery`'s constructor takes the summary.

- **`OBJECT` is a built-in scope token** (`admin-object-scope`). It is accepted under
  `strict-policy-tokens`, where it was refused at startup. An `admin.*` policy with a role entry other than `ALL` or
  `NONE`, an undeclared area or an action that is not a tier now fails startup.

- **BREAKING — a malformed delegation claim refuses the token** (`admin-delegation-claim`). A token whose `act`
  claim is present but not an object with `sub` (or nests deeper than 5) is now `401`, counted as
  `ludwig_security_authentication_total{reason="malformed-actor-claim"}`. If the identity provider uses `act` for
  something else, set `ludwig.security.jwt.actor-claim` to its real name or to blank.
- **BREAKING (Java API) — `Actor` and the audit search types gain `via`** (`admin-delegation-claim`). `Actor`'s
  canonical constructor takes `via` last (the factories are unchanged); `AuditTrailQuery`, `AuditTrailSearch.Filter`
  and `EventView` gain it too. `AuditTrailQuery`'s builder is unchanged.

- **BREAKING (Java API) — `MessagingAdminService`** (`messaging-db-consumer-controls`). The constructor takes the
  service's local consumer groups and `AdminAudit` besides the `LogStore`, and `consumers()` returns `ConsumerView`
  instead of the bare position. The JSON listing only gains fields. Services that let the auto-configuration build the
  service need no change.

- **BREAKING — operator-triggered reconciliation runs are asynchronous** (`reconciliation-run-history`).
  `POST .../runs` (a manual run of a key-addressed task, or any dry run) and `POST .../backfills` take the lease at
  once (`409` if held anywhere) and answer `202` with the run's real envelope and `Location: .../runs/{runId}`. The old
  answer was a fabricated, always-`SUCCEEDED` envelope after the whole pass. The dry-run report and the backfill counts
  are now the run's `result`. A full manual-run executor (`runs.manual-concurrency`, `2`) answers `429` with
  `retryAfterSeconds`. Running an enumeration, or an `async-job` task, stays synchronous and answers `200`.

  Public API changes:
  - `TaskRunner.dryRun` and `backfill` are replaced by `start(...)`, and the constructor takes a `RunRecorder` and the
    manual-run executor.
  - `ReconciliationAdminService.Engine` gains `runs`.
  - `ReconciliationMetrics` gains a default `recordRunRecordFailed`.
- **BREAKING — the outbox now deletes terminal messages by default** (`outbox-retention-purge`). Set
  `ludwig.outbox.retention.enabled=false` to keep them. `retention.published` is the idempotency window: once a
  published row is purged, a duplicate carrying its key is accepted and sent again. Without a `RunLock` the purge is
  not registered and startup logs a warning. `OutboxMessageRepositoryCustom` gains `purgeFinishedBefore`;
  `OutboxMetrics` gains a default `recordPurged`.
- **BREAKING — a cancelled file-action submission ends `CANCELLED`** (`file-action-cancelled-state`).
  `FileActionState` gains `CANCELLED`, mapped to `OperationStatus.CANCELLED`. A consumer switching over the enum or
  over `OperationResponse.detail()` must handle it. A `VALIDATED` submission is cancelled at once (`200`), while a
  queued or running one stops at its next checkpoint (`202`). `FileActionSubmissionQueryRepository` gains
  `cancelIfValidated` and `startIfValidated`.
- **BREAKING — administrative operation descriptions are required** (`admin-surface-polish`). Startup fails when an
  operation in the administrative catalog has a description key that resolves in no bundle, naming every such
  operation and key. A custom `AdminOperationContributor` must ship its keys in a bundle. Every in-repo area already
  does. `AdminHandlerValidator` takes `ProblemMessages` as a third constructor argument.
- **BREAKING — administrative surface** (`starter-admin-endpoints`, ships in the major release):
  - Administrative settings endpoints moved from `/admin/settings/**` to `/admin/v1/settings/**`, and their `as-of`
    parameter is now `asof`.
  - Another tenant's subject now answers `404` instead of `403`.
  - The self-service PAT API no longer accepts another owner; use `/admin/v1/pat/tokens` (`pat.privileged`).
  - `OutboxStatus` gains `DISCARDED`.
  - `audit-core`'s `Actor` gains a `tenant` component, which changes the canonical constructor.
  - `IdentityProjectionAutoConfiguration` now runs before the security auto-configurations.
  - `asyncapi-spring-boot-starter` scans with a classpath-tolerant `ComponentClassScanner`.
- **Adding `asyncapi-spring-boot-starter` changes a service's OpenAPI document once:** Springwolf registers
  a schema-title converter in swagger's shared registry, so every schema gains `title:`. Refresh and
  review the document when adopting it. `DefaultOutboxEventPublisher` takes an `OutboxEventTypes` as its
  last constructor argument (`OutboxEventTypes.none()` for none). The BOM pins swagger-core 2.2.26 so that
  springdoc and Springwolf converge.
- **BREAKING - named Kafka clusters** (`add-kafka-clusters`, shipped as a major release):
  - `MessagingMetrics` methods take the cluster as their first argument, and every `ludwig.messaging.*`
    meter carries a `cluster` tag.
  - `ConsumerActivityMonitor` is no longer a `RecordInterceptor`. `forCluster(cluster)` returns one, and
    `watch`/`silent` take the cluster.
  - `AuditingDeadLetterRecoverer` takes the cluster it reads from.
  - `DeadLetterTemplates.create(KafkaClusterProducers, String cluster, ObjectMapper)` replaces
    `create(KafkaProperties, ObjectMapper)`.
  - `ListenerContainerFactoryBuilder` and `MessagingConfigurationValidator` take `KafkaClusters` (and the
    builder `KafkaClusterProducers`) instead of `KafkaProperties`.
  - `ResolvedConsumerSettings` gains `cluster` and `deadLetterCluster`.
  - `OutboxDispatcherRegistry` takes `OutboxDispatcherGroup`s as well as dispatchers.
  - `DefaultOutboxEventPublisher` takes a `CorrelationProvider` and the correlation header name.
- **`crud-service-example` checks roles on the application service.** The `@PreAuthorize` of
  `get`, `search`, `update` and `delete` moved from `ProductController` to `ProductServiceImpl`, so REST
  and gRPC pass the same check; REST answers are unchanged. `create` keeps its check on the controller,
  because the spreadsheet import applies rows on a worker with no caller.
- `ludwig-bom` pins `guava` 33.6.0-jre, `error_prone_annotations` 2.50.0 and `j2objc-annotations` 3.1,
  which gRPC otherwise brings to a service at conflicting versions.

- **`ludwig-service-parent` fails a service that has a `test-support` artifact outside test scope.**
  A new enforcer rule bans `ru.ludwigandreas:test-support*` at compile and runtime scope. A service
  that declares `test-support`, `test-support-security` or `test-support-local` without
  `<scope>test</scope>` now fails at `validate`; both reference services already comply.
- **`test-support-local`: `PartnerStub.clientName()` is now `name()`.** The stub's name is no longer
  necessarily a rest-client name. The launcher also refuses a stub whose address property no other
  source configures, whatever kind of address it has.

### Removed

- **Actuator administration** (`starter-admin-endpoints`). The `reconciliation` and `fileingest` actuator
  endpoints are removed, along with their auto-configurations and `ludwig.reconciliation.endpoint.*` /
  `ludwig.ingest.endpoint.*`; use their `/admin/v1/` areas instead. Also removed:
  `ludwig.user-settings.access.admin-authority`, `ludwig.user-settings.web.admin-base-path`, and
  `PatAuthorities.MANAGE_ANY` (`pat:manage:any`).
- **BREAKING - the reason the next release is 3.0.0.** `rest-client-spring-boot-starter`'s
  `ru.ludwigandreas.restclient.auth.CachedToken` and `CachingTokenSource` are removed **without a prior
  deprecation release**, by decision. They moved unchanged to `outbound-token-core`: import
  `ru.ludwigandreas.outboundtoken.CachedToken` and `ru.ludwigandreas.outboundtoken.CachingTokenSource`.
  The API-compatibility check records both removals as justified exceptions.

- **BREAKING (wire) - Kafka envelope headers have one spelling.** `outbox-spring-boot-starter` writes
  only the `ludwig-*` envelope headers. `messaging-spring-boot-starter`'s `EnvelopeReader` and
  `VersionGatingDeserializer` read only those. The pre-prefix `event-type`, `event-version`,
  `idempotency-key` and `trace-id` are no longer written or read, and `MessageHeaders.LEGACY_*` is
  removed: use the constant of the same suffix (`MessageHeaders.EVENT_TYPE`, ...). **Before upgrading:**
  no consumer older than 1.1.0 may still be deployed, and no topic a consumer may replay may still hold
  records produced before 1.1.0. Such a record reads as having no type, which means it is ignored as
  an unknown type. Every release since 1.1.0 has written both spellings.
- **BREAKING - `odata-filter-spring-boot-starter` has no advice of its own.** `ODataFilterExceptionHandler`
  and the `odata.filter.web.problem-detail-advice-enabled` property are removed.
  `web-core-spring-boot-starter` is now a required dependency, so query errors are always rendered,
  localized, by the shared `ProblemDetail` pipeline. The new dependency brings `spring-web`, never
  `spring-webmvc`. An application advice of your own still takes precedence.
- **BREAKING - `observability-spring-boot-starter`: each record has one constructor.** The 19-argument
  `LogFieldNames` constructor and the `JsonLogEncoderConfig` constructor without a `BuildIdentity` are
  removed. Use the canonical constructors and pass `null` for `commitId` or `BuildIdentity.absent()` for
  `build`, which is exactly what the removed constructors did.
- **BREAKING - `audit-core`: one redaction marker.** `Redaction.LEGACY_SETTINGS_MASK` and
  `Redaction.LEGACY_HEADER_MASK` are removed. `Redaction.isMask` is now `true` for `Redaction.MASK`
  alone. The persisted `[redacted]` was rewritten by `audit-0004` in 2.0.0.
- The API-compatibility check records each of these removals as a justified exception
  (`remove-compatibility-shims`).

### Fixed

- **A cancelled file-action submission is no longer recorded as applied** (`file-action-cancelled-state`). A pass that
  stopped on a cancel was recorded `APPLIED` (envelope `SUCCEEDED`) with a successful `file-action.applied` event. It
  is now `CANCELLED`, audited as `file-action.cancelled`. A cancel that arrived while the submission was queued or
  being read is honoured before anything is applied, including `ALL_OR_NOTHING` and document actions. A cancel and a
  confirm racing on one validated submission can no longer both take effect.
- **`GET /admin/v1/me/operations` is published as `application/json`** (`admin-surface-polish`), not `*/*`, so a
  client generated from the OpenAPI document has a response type.
- **A PAT revoke no longer fails while the token is in use** (`gate-hygiene`). The asynchronous last-use write
  saved the whole row and bumped its version, so a revoke or rotation overlapping a use failed with an
  optimistic-lock conflict (`500`), and the token kept working. The write is now a version-less update of
  `last_used_at`, `last_used_ip` and `use_count` only, which never moves the time backwards, and `PatEntity` writes
  only changed columns. `PatQueryRepository` gains `recordUse`, and `PatUsageTracker` takes a `PatQueryRepository`
  instead of a `PatRepository`.
- **rest-client retries every timed-out attempt** (`gate-hygiene`). On the JDK transport the HttpClient's own
  deadline (`java.net.http.HttpTimeoutException`) was not classified as retryable, so a timed-out GET was retried
  only when Spring's future timeout happened to fire first. Both are now retryable timeouts.
- **`scripts/check_image_pins.sh` checks code, not prose, in Markdown** (`gate-hygiene`). Only fenced blocks and
  inline code are examined, so a sentence such as "no new image: the mail server" no longer fails the gate. A
  `--self-test`, now run by `scripts/gate.sh`, proves each trigger still fires.
- **Outbox records carry the publisher's `X-Correlation-Id`.** No outbox-produced record carried one
  before, on any transport: the poller thread has no request context, and audit-core's
  `CorrelationProvider` had only an empty fallback. observability-spring-boot-starter now provides the
  provider, and the outbox captures the id when the event is published. Audit events get their
  correlation id from the same provider.
- **`security-spring-boot-starter`: a configured audience is now enforced when the decoder is built
  from `issuer-uri`. THIS CHANGES WHAT A DEPLOYMENT ACCEPTS.** `ludwig.security.jwt.audiences` was
  applied only to a `NimbusJwtDecoder` bean. With
  `spring.security.oauth2.resourceserver.jwt.issuer-uri` Spring Boot's decoder bean is a lazy
  `SupplierJwtDecoder`, so the check was silently skipped and a token the same issuer minted for
  another service was accepted. The check is now installed on any `JwtDecoder`. A caller that was
  presenting a token for another audience starts receiving 401, which is what the configuration
  always said.
- **`identity-projection-spring-boot-starter`: the Kafka consumer starts.** With
  `ludwig.identity.kafka.enabled=true` an application failed on a missing
  `identityKafkaListenerContainerFactory`, because the auto-configuration was evaluated before the
  messaging one whose bean its condition looks for. It is now ordered after it.
- **`user-settings-spring-boot-starter`: present with no mode enabled, it no longer stops start-up.**
  Its hot-reload auto-configuration is now conditional on `ludwig.user-settings.enabled` and on a
  mode being enabled, like the configuration it serves.
- **`test-support`: a shared container can no longer be stopped by a context or a test.**
  `Containers`' per-JVM singletons ignore `stop()` and `close()`. A context holding one as a bean used
  to stop it on close, or on a failed load, for every other context in the JVM.

## [2.0.0]

A **major** release: it contains binary-breaking changes to published APIs, each marked **BREAKING**
below. Read those entries before moving a consumer from `1.1.x`.

### Added

- **Build provenance in every service artifact and on every log line.** `ludwig-service-parent` now packages
  `META-INF/build-info.properties` (group, artifact, name, version, build time, and the CI build number when
  the build is given `-Dludwig.ci.build-number`) beside `git.properties`, and
  `observability-spring-boot-starter` reads both at startup into a new `BuildIdentity` record. The
  abbreviated commit is written on every log event - `service.commit.id` (`ecs`), `ServiceCommitId` (`otel`),
  `commit_id` (`flat`) - and one `Application identity:` event at startup carries the full commit, branch,
  build timestamp, CI build number and dirty flag. An unresolved field is omitted, never written as
  `unknown`. **A log aggregator with a strict index template needs the new field mapped before a service
  carrying it writes to a shared index.** `BuildIdentity` is deliberately separate from `ServiceIdentity`,
  so none of it becomes a metric tag. `/actuator/info` gains a `build` section.
- **`observability-spring-boot-starter`: `ludwig.observability.build.*`**, six properties holding the resolved
  provenance; each defaults from the packaged resources and can be set explicitly by an artifact built some
  other way.
- **`architecture-rules`: the `logging` rule group**, on by default: no type outside the observability module
  implements a Logback `Encoder` or `Layout`, no type restates the build identity, and no production class
  launches an operating-system process or depends on JGit. A service's own `logback-spring.xml` is not
  affected. The third rule reports *every* process launch, because bytecode cannot say which program is
  run; a legitimate one is exempted by name in the rule.
- **`scripts/check_build_metadata.sh`**, run by the gate: fails when `ludwig-service-parent` stops being
  configured to produce either provenance resource.
- **`odata-filter-spring-boot-starter`: filter-policy discovery.** A client can ask what it may filter on
  instead of finding out by sending filters and reading the rejections - which was the only alternative, and
  is the behaviour `odata.filter.rejected` is documented as an alerting signal for. `GET <base-path>/<entity>`
  returns the property paths the caller may name, each one's type, the operators permitted on it, whether it
  is sortable, and the limits the server enforces. Off until `odata.filter.metadata.base-path` is set, and
  per-entity opt-in on top of that via `@FilterPolicy(metadataName = ...)`; an entity nobody published answers
  404, not an empty document. The document is filtered per caller - a path its roles do not allow is absent
  rather than listed as forbidden, and no role name appears in it - and it is a projection of the resolved
  policy, so it cannot drift from what the query path accepts. It is deliberately not called `$metadata` and
  does not claim to be CSDL.
- **`odata-filter-spring-boot-starter`: `odata.filter.metadata.served`**, a counter tagged by entity, so the
  clients that asked properly can be told apart from the ones probing.
- **`audit-core`: the `query` audit category.** A caller's read of a filterable collection, kept distinct
  from `access`, which records authorization decisions. "Was alice permitted to search" and "what did alice
  search for" are different questions, and an investigation normally needs to join them rather than find
  them merged.
- **`odata-filter-spring-boot-starter` now has an architecture test.** It had none, so the shared ArchUnit
  rule library - including the whole `audit` group, whose subject is this module - had never been applied to
  it. Two of its rules are switched off there as named debt, with the reason at the point of the disable:
  `cycles.modules-are-free-of-cycles` and
  `configuration-properties.configuration-properties-are-validated`.
- **`architecture-rules`: `audit.audit-types-produce-audit-events`.** Fails the build when a type in a
  module's `audit` package produces no `AuditEvent`. The two existing audit rules only caught a module that
  declared something *extra* - a second SPI, a second logger - and not one that declared nothing and also
  called no sink, which is how this module's trail reached nothing while the build stayed green.
- **`odata-filter-spring-boot-starter`: one call runs a filtered, ordered, counted page.**
  `ODataQueryExecutor` applies the predicate, the ordering, the offset, the limit and - unless the caller
  sent `$count=false` - the count, and `ODataSearch` lets a repository contribute its own predicates
  (where a data scope goes) and shape the content query (where a fetch join goes) without writing any of
  those four steps. Both example services lost a `PathBuilder` field, an `orderSpecifiers(Sort)`, a
  reflective `orderSpecifier(String, boolean)` with an unchecked-cast suppression, a
  `PageableExecutionUtils` call and a private `count` - which they had been carrying identically. The
  executor also refuses a query root whose alias differs from the one the predicate is built against:
  that mismatch never failed in JPQL, it cross-joined the table to itself and returned rows that were
  quietly wrong.
- **`odata-filter-spring-boot-starter`: `ODataPaths` exposes the dynamic path walk** for a repository
  that must drive its own query and still needs the `$orderby` ordering expressions.
- **`odata-filter-spring-boot-starter`: `$count` is implemented.** `$count=false` skips the count query
  and the response then carries no total. The module previously documented this option as deliberately
  ignored, on the grounds that a flag nothing could act on bought the caller nothing - which was true
  until something here executed the query.
- **`odata-filter-spring-boot-starter`: `ODataQueryOptions`** carries the five query options as one
  springdoc-describable controller parameter, replacing five `@RequestParam` declarations per endpoint and
  the per-service `XSearchCriteria` records that were structurally identical across services.
- **`web-core-spring-boot-starter`: `PageResponse.offset`.** The absolute row offset applied, so a caller
  paging by an unaligned `$skip` can read its own position back out of the response.
- **`architecture-rules`: `persistence.dynamic-paths-are-not-hand-built`.** Fails the build when a
  service builds a QueryDSL `PathBuilder` for a caller-supplied property name instead of taking the
  resolution from the module that validated it.
- **`file-action-spring-boot-starter`: the platform's one path for a user-submitted file that performs a
  business action.** A person drags a spreadsheet in and orders are created. A service writes a typed
  `RowBinding`, one of two sealed handler shapes and a block of YAML; the module owns the upload edge, the
  size ceiling enforced while the body is read, magic-byte format detection, storage before parsing, the
  content-hash idempotency claim, the scanner seam, the bounded-memory spreadsheet reader, the
  `UPLOADED/VALIDATED/APPLIED` lifecycle on `web-core`'s operation envelope, the commit policy, the paged
  row rejects and downloadable annotated workbook, a generated template, deferred execution under a lease,
  retention and audit. Reading an untrusted XLSX goes through POI's `XSSFReader` SAX path only, under
  compression-ratio, entry-count and inflation ceilings decided from the ZIP directory before any XML is
  parsed; `.xls` is refused, because `HSSFWorkbook` has no streaming mode and supporting it would make the
  heap a function of a user-supplied file. Sized for an interactive upload - 25 MiB and 100,000 rows by
  default - and a larger file is refused with a message naming `file-ingest-spring-boot-starter`.
- **Two declarations a deployment must make for itself.** `commit-policy` has no default, because
  `PER_ROW` would silently apply two thirds of a journal entry and `ALL_OR_NOTHING` would refuse four
  hundred contacts over one mistyped address, and which is wrong depends on the domain; an action without
  one does not start. `ludwig.file-action.scanning.mode` defaults to `required`, so an application with no
  `FileScanner` bean does not start either - having no scanning is a choice to be made out loud rather than
  a gap nobody notices.
- **`RuleGroup.UPLOADS` in `architecture-rules`.** `MultipartFile` may appear only in
  `file-action-spring-boot-starter`. Before this change there was not one in the repository, and the first
  service to need an upload would have written the predictable mistakes: no ceiling before the body is
  read, `getBytes()`, a DOM workbook read, and a 400 carrying three thousand row errors.
- **A working `product-import` in `crud-service-example`.** A product sheet becomes products through
  `ProductService` - an import is a different way in to an action the service already performs, not a
  second implementation with a table of its own.
- **One caller-preference contract in `web-core`.** `ru.ludwigandreas.webcore.preference` resolves the
  caller's locale and zone from their stored setting, then the request's headers, then configuration,
  and publishes both into `LocaleContextHolder`. A mapper reaches it by naming
  `UserPreferenceFormatter` in `@Mapper(uses = ...)`, which MapStruct then selects by type - no mapping
  signature changes and no `@Mapping` annotation. Outside a mapper it is `UserPreferences.current()`,
  with `UserPreferences.bind()` to carry a captured caller's preferences onto a worker thread.
- **`user-settings-spring-boot-starter` contributes the stored half.** A service that declares
  `WellKnownSettings.LOCALE` and `TIMEZONE` now has them honoured by every response, rather than only
  stored. A value is treated as the user's choice only when its `SettingLayer` is more specific than
  `PLATFORM`, so a setting nobody has touched does not outrank the caller's `Accept-Language`.
- **Two ArchUnit rules**, in a new `RuleGroup.PRESENTATION`: nothing may call `Locale.getDefault()`,
  `TimeZone.getDefault()`, `ZoneId.systemDefault()` or `Clock.systemDefaultZone()`, and nothing may
  declare a second type holding the caller's locale-and-zone pair.

- **`notification-service`: in-app notifications, the one passive channel.** A recipient who wants no
  email, chat or webhook can still be told something. `IN_APP` is a delivery channel like the others -
  same request contract, same template layout, same preference and suppression handling - but it is
  classified `PASSIVE`, and that classification is a mandatory constructor argument on `ChannelType`, so
  a new channel cannot be added without deciding it. Being passive means it does not interrupt: quiet
  hours do not defer it, because there is nothing to wake anybody up. Delivery settles the moment the
  item is written, since there is no provider to accept it. The inbox is the reader: list, unread count,
  mark one or all read, mark seen, and dismiss, each scoped to the caller and never taking an owner
  parameter - so one caller cannot read another's inbox by changing a path. A foreign item and a missing
  one are both 404, deliberately indistinguishable. Every transition is idempotent.
- **`notification-service`: platform announcements.** One announcement addressed to everybody, or to
  everybody holding a role, rather than a notification per person. The distinction is the design: a
  notification fans out on write (N rows, one reader each), an announcement fans out on read (one row, N
  readers), so publishing to the whole organisation is a constant number of statements and so is purging
  it. A recipient sees the announcements their roles allow, dismisses them individually, and a dismissal
  is a row that exists only for the people who actually dismissed. Content is stored per supported
  locale. The audience is resolved once at publish against a configured allowlist, so a role nobody
  authorized cannot be targeted, and the role codes are normalized at that point - a role-targeted
  announcement that matched nobody was the defect that found. A category may also fan out by email,
  which is a long-running operation reported through the platform's one `OperationResponse` envelope,
  cancellable cooperatively, answering 202 because the stop is only requested and committed deliveries
  are not recalled. Visibility is a window, and retention is anchored to the end of it rather than to
  creation, so an announcement published for next quarter is not purged before it appears and one still
  showing never vanishes from under the people reading it.
- **Both services publish their OpenAPI document into the tree**, at
  `docs/api/<artifactId>.openapi.yaml`, generated from the service's real application context rather
  than written by hand. The point is reviewability: a service's HTTP surface otherwise exists only
  inside a running process, so a change that widens a response, drops a field or alters a status code
  shows a reviewer nothing. An `ApiDocumentIT` per service regenerates the document at `verify` and
  fails the build when the committed copy disagrees with what the service serves; an ordinary build
  never rewrites it, so refreshing is deliberate and the diff is always asked for. Each document is
  canonicalized first - every object's members sorted, `servers` stripped - because springdoc's
  handler-walk order is not promised to be stable and unsorted output would produce diffs that are pure
  reordering. These documents describe what the services currently serve; they are explicitly not a
  compatibility baseline, which remains `scripts/check_api_baseline.sh`'s job for the jars.
  `crud-service-example` gained springdoc to make this possible, which also activated
  `odata-filter-spring-boot-starter`'s customizer there for the first time - so the reference service's
  search endpoint now documents its five OData query options, as the `odata-query-contract` capability
  already required but nothing could check.

### Changed

- **`observability-spring-boot-starter`: the console format default now depends on the active profile.**
  JSON everywhere, human-readable text under the `local` profile - so a service started with
  `--spring.profiles.active=local` gets a text console where it previously got JSON. An explicit
  `ludwig.observability.logging.json.enabled` still wins in either direction, so a service that already
  sets it for `local` is unaffected and can drop the override.
- **`observability-spring-boot-starter`: the text format is no longer Spring Boot's default pattern.** With
  JSON off, each line now carries `[name/version@commit env=... instance=...]` and
  `[correlation id,trace id,span id]`; previously switching format lost all of it. A pattern set through
  `logging.pattern.console` / `logging.pattern.file`, and any appender with a hand-configured encoder, is
  left as it is.
- **`observability-spring-boot-starter`: `spring.main.banner-mode` defaults to `off` when the format is
  JSON.** Spring Boot prints the banner straight to standard output, around the logging system, so a
  structured stream previously opened with seven lines of ASCII art. An explicit `spring.main.banner-mode`
  wins.
- **`ludwig-service-parent`: `git.properties` is narrowed to five keys** - commit id, abbreviated commit id,
  branch, commit time, dirty flag. It previously also carried the committer's name and e-mail address, the
  build host name and the remote URL, all of which `/actuator/info` served. A build with no git checkout now
  packages no `git.properties` at all, rather than an empty one.
- **`ludwig-service-parent`: `maven.build.timestamp.format` is set** to day precision, because it feeds the
  build time in `build-info.properties` and a full-precision time would give every build of one commit a
  different image digest. A service that reads `${maven.build.timestamp}` for its own purposes inherits the
  format and should override the property.
- **BREAKING for a database that already applied these changesets - every Liquibase changeset in the
  platform is now formatted SQL.** All 84 changesets across 13 modules moved out of Liquibase's XML
  change vocabulary into `--liquibase formatted sql` files, one changeset per file, named
  `NNNN-<slug>.sql` with the changeset id `<prefix>-NNNN-<slug>` and the author `ludwig-<prefix>`, so
  the filename and the `DATABASECHANGELOG` row cannot disagree. Each module keeps one XML root
  changelog holding `<include>` elements and comments only - it stays XML because Liquibase 4.27's
  formatted-SQL parser has no include directive. **Every root changelog keeps its existing path**, so
  no consumer's `<include>` line, `spring.liquibase.change-log` setting or module property changes.
  What a consumer sees instead is the DDL itself, in the file, rather than a vocabulary that generates
  it - and 46 of those changesets already wrapped a raw `<sql>` block, so the repository was half SQL
  already with no rule saying which to use.

  **Four changeset-id namespaces were corrected** in the same pass, because the author is now derived
  from the prefix: `ingest-` became `file-ingest-`, `usrset-` became `user-settings-`,
  `notification-service`'s `0004-N-` scheme became `notification-00NN`/`ludwig-notification` like its
  five siblings, and the reconciliation test changelog became `reconciliation-test-`. Sequence numbers
  were renumbered to be unique and ascending within each module, which closed
  `crud-service-example`'s two changesets numbered `003` and two numbered `004` and
  `reconciliation`'s `004b`.

  **If a database has already applied the XML changesets**, its next startup fails on a checksum
  mismatch rather than a corrupt schema. Recovery is `liquibase clearChecksums` followed by a normal
  `update`; for the four renamed namespaces above, the `DATABASECHANGELOG` rows also need their `ID`,
  `AUTHOR` and `FILENAME` updated to the new values, or those changesets re-run. This change was made
  on the recorded basis that no such deployment exists.

- **Every changeset now declares `dbms:postgresql` and an explicit rollback.** The rollback is either
  real SQL or `--rollback NOT REQUIRED`, which Liquibase parses to the same empty rollback as XML's
  `<rollback/>`; 37 of the 84 previously declared none at all, so nothing distinguished "irreversible
  by nature" from "nobody thought about it". The dialect declaration makes a non-PostgreSQL target
  skip a changeset rather than fail halfway through the schema. `pat-0002`'s rollback now also drops
  `ux_ludwig_pat_key_id`, which the XML version left behind.

- **BREAKING - `odata-filter-spring-boot-starter`: `ODataFilterProperties` moves from `..config..` to
  `..properties..`.** Only the import changes. It was the single class holding the module's entire
  package-cycle problem: `core`, `web` and `policy` each read the bound properties while the wiring in
  `config` references every package in the module, and those three inbound edges closed **twenty** distinct
  cycles through `config` - every cycle the module had. One move removed all twenty. The auto-configuration
  classes deliberately stay in `config`, because an auto-configuration class's fully-qualified name is
  configuration API: a deployment switches one off with `spring.autoconfigure.exclude=...` in YAML, which no
  compiler checks, so renaming that package would break an exclusion silently at runtime.
- **`odata-filter-spring-boot-starter`: bound properties are validated at startup.** `maxDepth`,
  `maxPageSize`, `defaultPageSize`, `maxNestedPropertyDepth` and `maxExpressionLength` must each be at least
  1, and none of them was checked before. A `max-page-size` of 0 was accepted and then failed *every* query
  at the first request, with a message naming `$top` - pointing an operator at the caller rather than at the
  line of configuration that caused it. `hibernate-validator` is taken as an **optional** dependency, so it
  does not reach a consumer; every in-repo consumer already ships one.
- **`odata-filter-spring-boot-starter`: its `ArchitectureTest` now disables nothing as debt.**
  `cycles.modules-are-free-of-cycles` and
  `configuration-properties.configuration-properties-are-validated` were both switched off when the module
  first got an architecture test; both are enabled.
- **`odata-filter-spring-boot-starter`: a filter application is recorded to `audit-core`'s single
  `AuditSink`**, under the `query` category, instead of being published only as a Spring application event
  that each consumer had to forward itself. The event is still published as an in-process hook. Whether a
  sink outage fails the query is `AuditFailurePolicy`'s decision, resolved from the deployment's
  configuration - this module wraps the sink call in no `try`/`catch`.
- **BREAKING - `odata-filter-spring-boot-starter`: `ODataQueryExecutor`, `ODataSearch` and `ODataPage` move
  from `..querydsl..` to `..execution..`.** `core` already depended on `querydsl`, so the executor
  depending on `core` from inside `querydsl` was a package cycle - introduced by the previous change and
  caught by this one's new architecture test. Only the import changes.
- **`odata-filter-spring-boot-starter`: `FilterSummary` lives in `..ast..`**, not `..audit..`. It describes
  a filter rather than an audit concept, and the new audit rule is right to insist that a type in a
  module's audit package produces the platform envelope.
- **BREAKING - `odata-filter-spring-boot-starter`: `ODataFilterService.parse` takes an
  `ODataQueryOptions`** instead of four positional parameters, two `String` and two `Integer`. The old
  signature let a call site transpose `filter` and `orderBy`, or `top` and `skip`, and still compile.
- **BREAKING - `odata-filter-spring-boot-starter`: `ODataQuery.predicate()` returns
  `Optional<Predicate>`** and is empty when the caller supplied no `$filter`, where it used to return
  `Expressions.TRUE`. An always-true predicate is indistinguishable from a caller's real one, which is
  why `export-spring-boot-starter` was appending an unconditional `AND true` to every unfiltered report.
- **BREAKING - `odata-filter-spring-boot-starter`: `ODataQueryArgumentResolver` and
  `odata.filter.web.argument-resolver-enabled` are removed.** The resolver was deprecated and off by
  default with no replacement; `ODataQueryOptions` is the replacement. Its request-reading half is kept
  verbatim, including the non-`$`-prefixed parameter aliases.
- **BREAKING - `web-core-spring-boot-starter`: `PageResponse`'s totals are nullable** (`Long`,
  `Integer`) and omitted from the JSON when the caller declined the count. Its canonical constructor
  gained `offset` and changed the totals' types; the factory methods are the supported way in and are
  source-compatible apart from the new `of(List, long, int, Long)`.
- **`LocaleContextHolder.getTimeZone()` now answers the caller's zone on a request thread.** It
  previously answered `TimeZone.getDefault()` - the container's zone - because
  `AcceptHeaderLocaleResolver` is a plain `LocaleResolver` and never publishes a
  `TimeZoneAwareLocaleContext`. **Anything that formatted a date from Spring's holder changes
  output**: a `@JsonFormat` without an explicit zone, a `@DateTimeFormat` conversion, a date argument
  interpolated into a message. This is the intended fix and it is still a behaviour change worth
  reading before upgrading. `ludwig.web.preferences.enabled=false` restores the previous behaviour
  exactly.
- **`Content-Language` carries the region the caller asked for.** A request for `ru-RU` against a
  service supporting `ru` is now answered `Content-Language: ru-RU` rather than `ru`. The body is
  still rendered from the `ru` bundle, which `MessageSource` falls back to on its own; the region is
  kept because the JDK carries first-day-of-week and the number separators as *region* data, so
  narrowing `ru-RU` to `ru` hands a Russian user an American calendar.
- **`export-spring-boot-starter` renders a report in the caller's zone.**
  `SecurityReportCaller.zone()` was a hard-coded `ZoneId.of("UTC")`, which was five hours out on every
  row for a user in Yekaterinburg. `ReportCaller` gains `preferences()`, and `locale()` and `zone()`
  become `default` methods over it - a source-compatible addition for a caller, and a method an
  implementor of that interface must now supply. An explicit `timeZone` in a report request still wins.


### Removed

- **BREAKING - `odata-filter-spring-boot-starter`: `FilterAppliedEvent`'s `rawFilter` and
  `resolvedPredicate` components.** Both were the caller's literal values verbatim - `rawFilter` is the
  `$filter` string as sent, and `resolvedPredicate` was `predicate.toString()` - so a filter on an e-mail,
  phone or document-number field put that value into whatever listened. They are replaced by a
  `FilterSummary` carrying the property paths and the operators and no value in any form: not hashed, not
  truncated, not collected.

### Fixed

- **`observability-spring-boot-starter`: the version fallback that could never fire.**
  `ServiceIdentityResolver` fell back to a `build.version` Environment property that nothing published. It
  now reads `build.version` from `META-INF/build-info.properties`, so a service run from an exploded layout
  reports its version.
- **Two ArchUnit rules that reported as passing while checking nothing.**
  `RuleGroup.CACHING`'s `noPrivateCaffeineCache` and `RuleGroup.KAFKA`'s
  `noPrivateDeadLetterRecoverer` were written as `noClasses().should(notDependOnClassesThat(..))`.
  `noClasses()` wraps the condition in ArchUnit's `never()`, which inverts every event, and the
  `ArchitectureConditions` helpers report only violations - so both produced zero findings over code
  that provably broke them. Both now use `classes().should(..)`, the reason is written at each site,
  and the new `PresentationRulesTest` asserts that a rule *names the offending class* rather than
  merely that it ran, which is the test shape that would have caught this.

## [1.1.0]

The **API compatibility baseline**. Everything published under this version is the floor every
later release is compared against, so a consumer can pin `1.1.0` and know what a `1.1.x` or `1.2.x`
is permitted to change.

### Added

- **An API-compatibility gate on every published library.** `revapi-maven-plugin` runs at `verify`
  for every module parented by the reactor root, comparing the module's compiled API against the
  newest release of the same coordinates in `ludwig.repo.releases`. The version increment is the
  permission: a major permits a binary-breaking difference, a minor permits a non-breaking one, a
  patch permits only an equivalent API. The two services get no comparison, because they are
  consumed as images rather than as jars.
- **A deprecation contract.** `@Deprecated` must declare both `since` and `forRemoval`, and must
  carry a Javadoc `@deprecated` tag naming the replacement. Enforced by two Checkstyle rules,
  `DeprecationWithoutSince` and `MissingDeprecated`, at `validate`.
- **Javadoc jars**, attached under `-Pci` beside the existing sources jars, for every library
  module. Malformed Javadoc — a broken `{@link}`, an unknown tag, a `@param` naming a parameter
  that does not exist — now fails the build; missing Javadoc remains the existing warning tier.
- **`CHANGELOG.md` and `CHANGELOG.ru.md`**, this pair, with an enforcer rule that fails a release
  build when either lacks a heading for the version being released.
- **`gitversion.yml` and a Jenkins version stage**, so the version is computed from the git history
  rather than typed into a file.
- **`scripts/check_image_pins.sh`**, wired into `scripts/gate.sh`: every container image reference
  in the `Jenkinsfile`, in YAML and in Markdown must carry a `sha256` digest, not a bare tag.

### Changed

- **A release is cut by tagging**, not by editing `.mvn/maven.config`. That file keeps a
  `-Drevision` value as the local, tag-less fallback only; a command-line user property beats it,
  which is the precedence CI relies on.
- **Every plugin taking part in the build declares a version.** `requirePluginVersions` is added to
  the enforcer in both the reactor root and `ludwig-service-parent`, and the plugins Maven and
  Spring Boot previously bound without one — `maven-jar-plugin`, `maven-resources-plugin`,
  `maven-clean-plugin`, `maven-install-plugin`, `maven-deploy-plugin`, `maven-site-plugin` — are
  pinned. Maven's own answer to an unpinned plugin is a warning that had been printed on every
  build of this repository without anyone acting on it.

### Fixed

- Twenty malformed Javadoc references across eleven modules, found by turning doclint on: three
  references to enum constants on a nested type, seven member-level `<h2>` headings where the
  implicit preceding heading is `<h3>`, three references to API that had been renamed or removed
  (`JiraClientBuilder#meterRegistry`, `request.IssueUpdate`, `AuthorityCache`), and several
  `{@link #getXxx()}` references to Lombok-generated accessors.
