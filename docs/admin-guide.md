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
| OIDC provider | every service with `security-spring-boot-starter` | `OIDC_ISSUER_URI`; its user stream (`corp.identity.users`) feeds the identity projection |
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
| identity-projection | `security_user`, `security_user_role`, `security_partner`, `security_partner_role`, `security_grant` | mirrors the provider |
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
| `corp.identity.users` | OIDC provider | identity-projection (every service with it enabled) | `corp.identity.users.dlt` |
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
| `/admin/v1/**` | security starter + each module | authenticated users and services holding a granted operation; see below |

**Administrative surface.** Every administrative endpoint lives under `/admin/v1/`. Modules declare `<area>.<tier>`
operations; each service grants them to its own roles under `ludwig.admin.grants` - there is no default grant, so an
upgraded service refuses every administrative call until the block exists, and its startup log lists the ungranted
operations. Administrative microservices get their roles from `ludwig.identity.service-roles`. Partners and personal
access tokens are refused; a `privileged` call needs an `Admin-Reason` header; every change is audited in category
`admin`. `GET /admin/v1/me/operations` tells a UI what the caller holds. Details: [security](../sources/security-spring-boot-starter/README.md#administrative-surface).
No module exposes an operator actuator endpoint any more: what `/actuator/fileingest` and `/actuator/reconciliation`
did is in the `fileingest` and `reconciliation` areas below.

| Area | Module | Read | Operate | Privileged |
|---|---|---|---|---|
| `jobs` | job-core | jobs, run leases | run a job now | force-release a run lease |
| `outbox` | outbox | messages (no payload), summary | retry one or many | discard |
| `messaging` | messaging-db | consumer lag | - | - |
| `reconciliation` | reconciliation | tasks, scopes, quarantine | runs, backfills, requeues, walks | cancel remote job, release lease, confirm sweep |
| `fileingest` | file-ingest | tasks, runs, checkpoints | start a run | - |
| `audit` | audit store | search the trail (30-day window) | - | - |
| `identity` | identity projection | partners, grants, a subject's authorities | - | partner and grant changes |
| `exports` | export | runs, subscriptions (no output) | cancel a run | - |
| `fileactions` | file-action | submissions (no file, no rows) | cancel a submission | - |
| `settings` | user-settings | a subject's settings and consents | scope writes, backfill | - |
| `pat` | pat | tokens' metadata | - | issue for another owner, revoke anybody's |

Each module's README has the full table of operations and endpoints under "Administration". A service grants only
the areas it has - a key naming an absent area fails startup:

```yaml
ludwig:
  admin:
    grants:
      "[jobs.read]": [NOTIFICATION_ADMIN, NOTIFICATION_SUPPORT]
      "[jobs.operate]": [NOTIFICATION_ADMIN]
      "[jobs.privileged]": [NOTIFICATION_ADMIN]
      # ... one line per operation of every area present
```

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
`ludwig.admin.approval.required: ["outbox.privileged", "identity.privileged"]`; the service needs job-core, whose
table keeps the approvals. A listed call answers `202` and waits in `GET /admin/v1/approvals` for a different holder
of the operation to approve (`POST /admin/v1/approvals/{id}/approve`), reject or let it expire after
`ludwig.admin.approval.ttl` (24 hours). Approving runs the stored request as the approver. **Break-glass:** an
operation left out of the list is the path for an emergency one person must take alone - keep the operation you would
need during an incident (a lease force-release, say) out of it, or keep a second administrator reachable; there is no
override header.

**Per-object scope.** A team can be limited to the objects it owns - today, reconciliation tasks - with a data-scope
policy for `admin.<area>` (roles `ALL` or `NONE`) and per-subject grants of dimension `object` through
`POST /admin/v1/identity/grants`. Out-of-scope reads answer `404`, like a missing object. Set-up and a worked
example: [data-scope configuration](data-scope-configuration.md#administrative-objects).

**Upgrading to this release.** In each service: add the `ludwig.admin.grants` block (the startup log line
"Administrative operations no role of this service holds" lists what is left); for administrative microservices add
`ludwig.identity.service-roles`; remove `reconciliation` and `fileingest` from `management.endpoints.web.exposure.include`;
delete `ludwig.reconciliation.endpoint.*`, `ludwig.ingest.endpoint.*`, `ludwig.user-settings.access.admin-authority` and
`ludwig.user-settings.web.admin-base-path`; move holders of `pat:manage:any` and `ROLE_EXPORT_ADMIN` to grants of
`pat.*` and `exports.*`; point administrative UIs at `/admin/v1/` (settings' `consents/as-of` is now `consents/asof`).
The schema changes are two additive changesets, `audit_event.actor_tenant` and `audit_event.actor_via`; rolling back
the release means the previous major.

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
| identity-projection | discarded events only logged; no projection metrics; grant and partner changes not audited | authorization data drift is invisible |
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
| Users or roles out of date | [identity-projection](../sources/identity-projection-spring-boot-starter/README.md#runbook) |
| Notification queue backing up, double sends | [notification-service](../services/notification-service/README.md#operational-runbook) |
| A file did not arrive or ingest stopped | [file-ingest](../sources/file-ingest-spring-boot-starter/README.md#runbook) |
| Partner calls failing, breaker open | [rest-client](../sources/rest-client-spring-boot-starter/README.md#runbook) |
| Configuration change not applied | [hot-reload](../sources/hot-reload-spring-boot-starter/README.md#runbook) |
| Report stuck or failed | [export](../sources/export-spring-boot-starter/README.md#runbook) |
