# Administrator guide

**English** · **[Русский](admin-guide.ru.md)**

For the people who deploy and run services built on this platform. Every module README has
**Configuration**, **Deployment**, **Observability** and **Runbook** sections; this guide is the
platform-wide view and the index into them.

## 1. What a deployment consists of

| Component | Needed by | Notes |
|---|---|---|
| PostgreSQL | every service | one database per service; Liquibase migrates it on start |
| Kafka | services with messaging enabled | one or more named clusters (`ludwig.kafka.clusters.<name>`); [messaging-db](../sources/messaging-db-spring-boot-starter/README.md) stands in for a cluster that does not exist yet |
| OIDC provider | every service with `security-spring-boot-starter` | `OIDC_ISSUER_URI`; its user stream (`corp.identity.users`) feeds the identity projection through `identity-source-kafka`, and a provider with a user API feeds it through `identity-source-directory` |
| Edge (nginx/Envoy) | services behind it | exchanges the session cookie for a JWT; forwards mTLS client certificates |
| SMTP server | notification-service | |
| S3-compatible storage | object-storage, file-ingest, export (when configured) | credentials only through the AWS SDK provider chain |
| Vault | optional | secrets as files via the Vault Agent Injector (preferred) or `ludwig.hotreload.vault.*` |
| OTLP collector | tracing | `MANAGEMENT_OTLP_TRACING_ENDPOINT` |
| Prometheus | metrics | scrapes `/actuator/prometheus` |
| Redis | optional | the cache starter's shared tier only |

## 2. Deploying a service

1. **Image.** Built by Jib (`ludwig-service-parent`) or a service `Dockerfile`. Deploy by **digest**,
   never by tag alone: every image reference in this repository is `name:tag@sha256:…`, and
   `scripts/check_image_pins.sh` fails the build otherwise. The release's digest goes into the manifest
   (notification-service's manifest carries a `REPLACE_WITH_RELEASE_DIGEST` placeholder for this).
2. **Manifest.** Start from
   [notification-service's manifest](../services/notification-service/deploy/k8s/notification-service.yaml):
   - liveness `/actuator/health/liveness` (the process only), readiness `/actuator/health/readiness`
     (`db` plus the service's own backlog indicator);
   - memory limit set, since the JVM sizes its heap from it, and the JVM exits on `OutOfMemoryError`;
   - a `PodDisruptionBudget` that keeps enough replicas for the leased maintenance jobs;
   - secrets by `secretKeyRef`, never in a ConfigMap;
   - `/tmp` as an `emptyDir` with a read-only root filesystem.
3. **Rollout.** Liquibase runs on start; each starter validates its configuration at startup and
   refuses to start on an unsafe combination, so a bad configuration stops a rollout instead of
   misbehaving. Watch readiness, not just pod status.
4. **Rollback.** Redeploy the previous image digest. Migrations are additive, so the previous version
   runs against the newer schema; where a module documents a destructive changeset (a table drop),
   its README says so under Deployment.
5. **Releases** are git tags; see [runbook.md §6](runbook.md#6-releasing). IFT deployment is not yet
   wired into the Jenkinsfile ([runbook.md §4.4](runbook.md#44-ift)).

## 3. Configuration and secrets

- All platform properties live under `ludwig.<module>.*`; each module README has the full table with
  defaults. Spring's relaxed binding applies: `ludwig.outbox.polling.enabled` is
  `LUDWIG_OUTBOX_POLLING_ENABLED`.
- **Hot reload** ([hot-reload](../sources/hot-reload-spring-boot-starter/README.md)): files listed in
  `ludwig.hotreload.files`, FreeMarker templates and Vault secrets reload without a restart.
  Kubernetes ConfigMap and Secret mounts reach the pod within the kubelet sync period (about a minute).
  An environment variable always overrides a reloaded value.
- **Secrets:** database credentials, client secrets, signing keys and SMTP credentials come from the
  secret store. The gRPC server refuses to start without a TLS bundle; the webhook channel refuses to
  start without a signing secret.
- Activation properties that switch a feature on by merely existing (for example
  `ludwig.hotreload.freemarker.template-directory`) are set as environment variables in the manifest,
  not as empty placeholders in `application.yml`.

## 4. Database

Each starter ships its changelog; the service includes it. Tables by module:

| Module | Tables | Retention |
|---|---|---|
| outbox | `outbox_message`, `outbox_status_history` | **none: published rows are never purged** (§9) |
| job-core | `job_run_lock` | bounded by lock names |
| idempotency | `idempotency_claim` | `ludwig.idempotency.ttl`, batched purge |
| audit starter | `audit_event` | `ludwig.audit.retention.*`, default `P7Y`, per category |
| identity-projection | `security_user`, `security_user_role`, `security_partner`, `security_partner_role`, `security_grant`, `identity_sweep_listed` | mirrors the providers |
| identity-source-directory | `identity_directory_state` | each directory's watermark and last sync and sweep |
| messaging-db | `message_log`, `partition_head`, `consumer_offset` (schema `ludwig_messaging`) | `ludwig.messaging.db.retention`, default 7d, consumed or not |
| export | `export_report_run`, `export_report_output`, `export_saved_report`, `export_report_subscription` | output retention per sink |
| file-ingest | `file_ingest_run`, `file_ingest_quarantine` | **none** (§9) |
| file-action | `file_action_submission`, `file_action_row_reject` | per action, e.g. `retention: P7D` |
| pat starter | `ludwig_pat` | purge exists but is not scheduled (§9) |
| reconciliation | `sync_*` (8 tables) | per module README |
| user-settings | `user_setting_value`, `user_consent`, `user_setting_audit` | consents are append-only |
| notification-service | `notification_*` | seven windows; see its README |

Locks held by a dead pod expire on their own (`ludwig.job-core.lock.default-lease`). To break one by
hand, clear the owner; **never delete the row**:

```sql
UPDATE job_run_lock SET owner = NULL, run_id = NULL, expires_at = now() WHERE lock_name = '<name>';
```

## 5. Kafka

| Topic | Producer | Consumer | Dead letters |
|---|---|---|---|
| `corp.identity.users` | OIDC provider | identity-source-kafka (every service subscribing it, under `ludwig.identity.kafka.subscriptions.<source>`) | `corp.identity.users.dlt` |
| `catalog.product-events` | crud-service-example (outbox) | - | - |
| `platform.notifications.requests` | any service | notification-service (off by default) | `platform.notifications.requests.dlt` |
| `platform.notifications.events` | notification-service (outbox) | - | - |

The authoritative list per service is its AsyncAPI document in [docs/api/](api/README.md).

- Every consumer built with the messaging starter dead-letters to `<topic>.dlt` after its retry budget;
  a record that cannot be deserialized goes there on its first attempt.
- Consumer lag and silence (`ludwig.messaging.silent`) are published per cluster and topic.
- **Replaying a dead letter:** fix the cause, then republish the DLT record to the source topic with
  its headers (the `ludwig-*` envelope headers carry the event id, so consumers with dedup drop
  anything already processed). See the [messaging runbook](../sources/messaging-spring-boot-starter/README.md#runbook).
- Multiple clusters are declared once under `ludwig.kafka.clusters`; outbox routes and listeners name
  the cluster.

## 6. Observability

| Signal | Where | Configured by |
|---|---|---|
| Logs | stdout, structured JSON (ECS / OTel / flat) outside the `local` profile, with service, version, commit, trace, span and correlation ids | `ludwig.observability.logging.*` |
| Traces | OTLP | `MANAGEMENT_OTLP_TRACING_ENDPOINT`, `ludwig.observability.tracing.*` |
| Metrics | `/actuator/prometheus` | Micrometer; `ludwig.observability.metrics.*` |
| Build identity | `/actuator/info`, `/server/info`, every log line | packaged by `ludwig-service-parent` |
| Log levels | changed at runtime through hot reload | |

**Metric names in Prometheus:** dots become underscores, counters gain `_total`, timers expose
`_seconds_count`, `_seconds_sum` and `_seconds_bucket`. `ludwig.outbox.deadlettered` is
`ludwig_outbox_deadlettered_total`.

**Alerts.** Each module README has a "Recommended alerts" table with ready PromQL. Start with these:

| Alert | Module |
|---|---|
| `increase(ludwig_outbox_deadlettered_total[15m]) > 0` | [outbox](../sources/outbox-spring-boot-starter/README.md#observability) |
| messaging lag rising, or `ludwig_messaging_silent` set | [messaging](../sources/messaging-spring-boot-starter/README.md#observability) |
| `increase(ludwig_job_lock_lost_total[15m]) > 0` | [job-core](../sources/job-core/README.md#observability) |
| oldest pending delivery older than threshold | [notification-service](../services/notification-service/README.md#metrics-and-the-two-that-matter) |
| `ludwig.ingest.missing` (an expected file did not arrive) | [file-ingest](../sources/file-ingest-spring-boot-starter/README.md#observability) |
| freshness lag above SLO | [reconciliation](../sources/reconciliation-spring-boot-starter/README.md#observability) |
| circuit breaker open on a named client | [rest-client](../sources/rest-client-spring-boot-starter/README.md#observability), [grpc-client](../sources/grpc-client-spring-boot-starter/README.md#observability) |
| HTTP 5xx rate | Boot's `http_server_requests_seconds_count{status=~"5.."}` |

## 7. Audit

- Every module records through one `AuditSink` ([audit-core](../sources/audit-core/README.md)).
  Without `audit-spring-boot-starter`, events go to the SLF4J sink (logger
  `ru.ludwigandreas.audit`) and live as long as your log retention.
- With the [audit starter](../sources/audit-spring-boot-starter/README.md), events are written to
  `audit_event` in the caller's transaction, optionally shipped to a SIEM through the outbox, and purged
  per category (`ludwig.audit.retention.default-period`, default seven years; override per category
  with `ludwig.audit.retention.by-category.<category>`).
- Attribute values classified as sensitive are replaced by `***REDACTED***` before any sink sees them.
- Which module emits which category and action is in audit-core's category table.

## 8. Actuator, operational and administrative endpoints

| Endpoint | Provided by | Exposure |
|---|---|---|
| `/actuator/health/liveness`, `/actuator/health/readiness` | observability starter + Boot | public (probes) |
| `/actuator/info`, `/server/info` | observability starter | public |
| `/actuator/prometheus`, `/actuator/metrics` | Micrometer | internal network only |
| `/admin/v1/**` | security starter + each module | authenticated users and services holding the operation; see below |
| `/me/operations` | security starter | any authenticated caller: the operations it holds |

**Administrative surface.** Every administrative endpoint lives under `/admin/v1/` and is guarded by one
operation of sensitivity `READ`, `CHANGE` or `PRIVILEGED`. Which of the service's roles hold it is the service's own
`ludwig.security.roles` (below) - there is no default, so an operation no role lists is refused to everyone, and the
startup log line "Operations no role of this service holds" lists them. Administrative microservices get their roles
from `ludwig.identity.service-roles`. Partners are refused; a `PRIVILEGED` call needs an `Operation-Reason` header
(gRPC metadata `operation-reason`) and refuses personal access tokens unless
`ludwig.security.operations.allow-personal-access-tokens=true`; every change is audited in category `admin` with the
operation id as its action. `GET /me/operations` tells a UI what the caller holds, with what scope;
`GET /admin/v1/security/operations/{id}/holders` (`security.operation.read`) lists the roles holding an operation.
Details: [security](../sources/security-spring-boot-starter/README.md#administrative-surface). No module exposes an
operator actuator endpoint any more: what `/actuator/fileingest` and `/actuator/reconciliation` did is in the
`fileingest` and `reconciliation` areas below.

| Path | Module | `READ` | `CHANGE` | `PRIVILEGED` |
|---|---|---|---|---|
| `/admin/v1/jobs` | job-core | `job.definition.read`, `job.lease.read` | `job.definition.run` | `job.lease.release` |
| `/admin/v1/replicas` | job-core | `replica.instance.read`, `replica.command.read` | - | - |
| `/admin/v1/caches` | job-core + cache | `cache.definition.read` | `cache.performance.evict` | `cache.security.evict` |
| `/admin/v1/hotreload` | hot-reload + job-core | `hotreload.source.read` | `hotreload.source.reload` | - |
| `/admin/v1/restclient` | rest-client + job-core | `restclient.breaker.read`, `restclient.client.read` | `restclient.token.reset` | `restclient.breaker.set` |
| `/admin/v1/outbox` | outbox | `outbox.message.read` | `outbox.message.retry` | `outbox.message.discard` |
| `/admin/v1/messaging` | messaging-db | `messaging.consumer.read` | `messaging.consumer.pause`, `messaging.consumer.resume` | `messaging.offset.move` |
| `/admin/v1/reconciliation` | reconciliation | `reconciliation.task.read` | `reconciliation.run.start`, `reconciliation.backfill.start`, `reconciliation.cursor.reset`, `reconciliation.scope.reset`, `reconciliation.quarantine.requeue`, `reconciliation.member.requeue`, `reconciliation.remotejob.requeue`, `reconciliation.discovery.start`, `reconciliation.walk.start`, `reconciliation.walk.preview`, `reconciliation.sweep.discard` | `reconciliation.remotejob.cancel`, `reconciliation.lease.release`, `reconciliation.sweep.confirm` |
| `/admin/v1/fileingest` | file-ingest | `fileingest.task.read` | `fileingest.run.start` | - |
| `/admin/v1/audit` | audit store | `audit.event.read` | - | - |
| `/admin/v1/identity` | identity projection | `identity.partner.read`, `identity.grant.read`, `identity.authority.read`, `identity.source.read` | - | `identity.partner.onboard`, `identity.partner.replace`, `identity.partner.suspend`, `identity.partner.activate`, `identity.grant.create`, `identity.grant.revoke` |
| `/admin/v1/exports` | export | `export.run.read`, `export.subscription.read` | `export.run.cancel` | - |
| `/admin/v1/fileactions` | file-action | `fileaction.submission.read` | `fileaction.submission.cancel` | - |
| `/admin/v1/settings` | user-settings | `settings.subject.read`, `settings.consent.read` | `settings.scope.set`, `settings.backfill.start` | - |
| `/admin/v1/pat` | pat | `pat.token.read` | - | `pat.token.issue`, `pat.token.revoke` |
| `/admin/v1/security` | security | `security.operation.read` | - | - |

Each module's README has the full table of operations, endpoints and descriptions under "Administration".

### Roles and operations

Code names operations, never roles: a role is only a configuration item. Every operation a service runs - its own
business operations and every administrative one of the modules it uses - is held through one table,
`ludwig.security.roles.<ROLE>`, with `operations` (operation id to scope) and optionally `includes` (roles whose
operations it also holds). A scope is `ALL`, or dimensions joined by `+` that must all admit a row: `OWN`, `TENANT`,
`PARTNER`, `OBJECT`, or one a module contributes. Roles' scopes are united, so a role can only widen what another
grants.

```yaml
ludwig:
  security:
    roles:
      NOTIFICATION_SUPPORT:
        operations:
          notification.delivery.read: TENANT     # a business operation: only the caller's tenant's rows
          notification.delivery.cancel: TENANT
          outbox.message.read: ALL               # an administrative one: an operation that touches no rows is ALL
          job.definition.read: ALL
          audit.event.read: ALL
      NOTIFICATION_ADMIN:
        includes: [NOTIFICATION_SUPPORT]         # every operation of the support role, then widened below
        operations:
          notification.delivery.read: ALL
          notification.delivery.retry: ALL
          outbox.message.retry: ALL
          outbox.message.discard: ALL
          job.definition.run: ALL
          job.lease.release: ALL
      SUPPLY_OPS:
        operations:
          reconciliation.task.read: OBJECT       # only the tasks granted to each person, see below
          reconciliation.run.start: OBJECT
      NOTIFICATION_SENDER:
        operations:
          notification.request.read: OWN         # a peer service reads back only what it submitted
```

Startup fails on an entry naming an operation the service does not catalogue (a typo, or an area the service does
not have), a dimension the operation's resource does not bind, a dimension other than `ALL` or `OBJECT` on an
operation that touches no rows, a wildcard, `NONE`, or an `includes` cycle or undefined role. A role name the table
does not define is not an error: roles come from the identity provider. The roles evaluated are the authority
resolver's (the identity projection, `ludwig.identity.service-roles`), never a token claim.

**Administration through a UI: the `act` claim.** When people administer services through an administrative UI,
have the identity provider issue the UI's calls as an RFC 8693 token exchange, so the token carries
`act: {sub: admin-console}` (the UI's client id). Every audit event of such a call records the person as the actor
and `admin-console` as `via`, and the trail can be searched with `GET /admin/v1/audit/events?$filter=actorVia eq 'admin-console'`. If
the identity provider names the claim differently, set `ludwig.security.jwt.actor-claim`. A token whose claim is
present but not an object with `sub` is refused with `401`: when requests start failing after an upgrade and
`ludwig_security_authentication_total{outcome="failure",reason="malformed-actor-claim"}` rises, check this first, and set `actor-claim` to blank if the
provider uses `act` for something else. To require that people come only through the UI, set
`ludwig.admin.human-callers.required-via: [admin-console]`; a person calling directly is then refused `403` and the
refusal is audited. Service principals are never affected, so keep a break-glass service principal holding the
operations you need during a UI outage, and clear the list if people must act directly.

**Filtering administrative listings.** The audit trail, outbox messages, reconciliation runs, export runs, file-action
submissions and PATs filter with OData `$filter` only, for example
`GET /admin/v1/outbox/messages?$filter=status eq 'DEAD_LETTER' and eventType eq 'OrderCreated'`, and page with
`cursor`/`size`. `$orderby`, `$top`, `$skip` and `$count` are refused, and so is a field outside each listing's
allow-list (free text, secrets and personal data never are on it). A filter never widens what the caller may see:
tenant confinement, the audit's 30-day default and a path's task apply underneath. Each module's README lists its
filterable fields. The former `?status=`, `?owner=` and similar parameters are gone and are ignored if sent.

**Four-eyes approval.** To require a second administrator for privileged actions, list their operations, for example
`ludwig.admin.approval.required: ["outbox.message.discard", "identity.grant.create"]` (only administrative
`PRIVILEGED` operations are eligible); the service needs job-core, whose
table keeps the approvals. A listed call answers `202` and waits in `GET /admin/v1/approvals` for a different holder
of the operation to approve (`POST /admin/v1/approvals/{id}/approve`), reject or let it expire after
`ludwig.admin.approval.ttl` (24 hours). Approving runs the stored request as the approver. **Break-glass:** an
operation left out of the list is the path for an emergency one person must take alone - keep the operation you would
need during an incident (a lease force-release, say) out of it, or keep a second administrator reachable; there is no
override header.

**Per-object scope.** A team can be limited to the objects it owns - today, reconciliation tasks - with a role
granting `OBJECT` on the area's operations (`SUPPLY_OPS` above) and per-subject grants of dimension `object` through
`POST /admin/v1/identity/grants`. Out-of-scope reads answer `404`, like a missing object; other calls `403`. A role
granting `ALL` covers every object. Set-up and a worked example:
[data-scope configuration](data-scope-configuration.md#administrative-objects).

**Upgrading to this release.** In each service: replace `ludwig.security.data.policies` with
`ludwig.security.roles`, declaring each business operation the policies covered, and grant every administrative
operation the service needs there (the startup log line "Operations no role of this service holds" lists what is
left). The application refuses to start while `ludwig.security.data.policies`, `ludwig.security.data.default-access`,
`ludwig.security.data.strict-policy-tokens`, `ludwig.admin.grants` or `ludwig.admin.allow-personal-access-tokens` is
set, naming the key. For administrative microservices add `ludwig.identity.service-roles`; remove `reconciliation` and
`fileingest` from `management.endpoints.web.exposure.include`; delete `ludwig.reconciliation.endpoint.*`,
`ludwig.ingest.endpoint.*`, `ludwig.user-settings.access.admin-authority` and
`ludwig.user-settings.web.admin-base-path`; move holders of `pat:manage:any` and `ROLE_EXPORT_ADMIN` to roles holding
`pat.token.*` and `export.run.*`; point administrative UIs at `/admin/v1/` (settings' `consents/as-of` is now
`consents/asof`), discovery at `/me/operations`, and the reason header at `Operation-Reason`. The schema changes are
two additive changesets, `audit_event.actor_tenant` and `audit_event.actor_via`; rolling back the release means the
previous major.

**Identity sources.** The identity projection no longer consumes Kafka itself. In each service: add
`identity-source-kafka-spring-boot-starter`, declare the source (`ludwig.identity.sources.oidc: {}` - `oidc` is the
`source_system` existing rows carry), move `ludwig.identity.kafka.topic` to
`ludwig.identity.kafka.subscriptions.oidc.topic` (the old key fails startup, naming the new one) and keep
`ludwig.identity.kafka.group-id`, now mandatory. A provider that serves users over an API is fed by
`identity-source-directory-spring-boot-starter` and one `IdentityDirectory` bean; `GET /admin/v1/identity/sources`
(`identity.source.read`) shows every source, its feeds and their state. A complete listing disables the users a source
no longer lists, and aborts with no change past `ludwig.identity.sweep.max-disable-ratio` (10%).

`ludwig.security.public-paths` replaces the default list rather than extending it: when adding a path,
restate the actuator entries or the probes will require authentication.

## 9. Known operational gaps

Verified against the code; each is also listed in the module's README under "Known gaps". Plan
monitoring and housekeeping around them until they are fixed.

| Module | Gap | Operational consequence |
|---|---|---|
| job-core | no per-job metrics (runs, failures, duration, last success) | a stalled worker in any module is invisible; alert on the module's own backlog instead |
| outbox | no backlog gauge, no health indicator; `PUBLISHED` rows never purged | table grows without bound; schedule your own purge |
| file-action | no metrics at all; problem codes use `file-action.` instead of `ludwig.file-action.error.` | imports visible only in logs |
| object-storage | no metrics or health for S3 calls | storage outages show up only as failures in callers |
| identity-projection | grant and partner changes made in SQL are not audited | authorization data drift is invisible |
| messaging-db | no write-rate or table-size metric, no health indicator | watch table size in the database |
| audit-core | no sink-failure metric (a `WARN` only) | alert on the log line |
| audit starter | no gauge for the oldest unshipped row | SIEM shipping delay is invisible |
| idempotency | no gauge for `IN_PROGRESS` claims | stuck claims are visible only by query |
| security, grpc-server | 401 / `UNAUTHENTICATED` not audited | failed authentication only in logs and metrics |
| pat starter | `destroyExpiredSecrets`, `endElapsedRotationOverlaps`, `revokeInactive` and `purge` never scheduled; no token-state metrics | `ludwig_pat` grows; expiry is still enforced at verification |
| export | no gauge for `PENDING` runs; no subscription metrics | |
| file-ingest | no retention for runs and quarantine; quarantine holds raw records (possibly personal data) | purge by hand under your data policy |
| grpc-server | no executor queue-depth gauge | |
| crud-service-example | no readiness group (readiness ignores the database) | copy notification-service's group |
| platform-wide | only notification-service contributes a `HealthIndicator` | readiness mostly reflects `db` only |

Dead configuration (accepted, does nothing): `odata.filter.web.problem-detail-advice-enabled`,
`ludwig.reconciliation.tasks.<name>.audit.persist` (and the `sync_audit_record` table),
`ludwig.user-settings.retention.audit`. Dead metric: `ludwig.restclient.audit.failures` is never
incremented.

## 10. Incident runbooks

Each module's README ends its operational part with a **Runbook** table (symptom → likely cause →
action). The ones most often needed:

| Situation | Runbook |
|---|---|
| Events not delivered, dead letters | [outbox](../sources/outbox-spring-boot-starter/README.md#runbook) |
| Consumer stuck, lag, DLT growing | [messaging](../sources/messaging-spring-boot-starter/README.md#runbook) |
| A scheduled job has not run, lock held | [job-core](../sources/job-core/README.md#runbook) |
| 401/403 storms, token rejected | [security](../sources/security-spring-boot-starter/README.md#runbook) |
| Users or roles out of date, a sweep aborted | [identity-projection](../sources/identity-projection-spring-boot-starter/README.md#runbook), then the source's feed: [Kafka](../sources/identity-source-kafka-spring-boot-starter/README.md#runbook) or [directory](../sources/identity-source-directory-spring-boot-starter/README.md#runbook) |
| Notification queue backing up, double sends | [notification-service](../services/notification-service/README.md#operational-runbook) |
| A file did not arrive or ingest stopped | [file-ingest](../sources/file-ingest-spring-boot-starter/README.md#runbook) |
| Partner calls failing, breaker open | [rest-client](../sources/rest-client-spring-boot-starter/README.md#runbook) |
| Configuration change not applied | [hot-reload](../sources/hot-reload-spring-boot-starter/README.md#runbook) |
| Report stuck or failed | [export](../sources/export-spring-boot-starter/README.md#runbook) |
