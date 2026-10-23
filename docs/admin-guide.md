# Administrator guide

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

## 8. Actuator and operational endpoints

| Endpoint | Provided by | Exposure |
|---|---|---|
| `/actuator/health/liveness`, `/actuator/health/readiness` | observability starter + Boot | public (probes) |
| `/actuator/info`, `/server/info` | observability starter | public |
| `/actuator/prometheus`, `/actuator/metrics` | Micrometer | internal network only |
| `/actuator/fileingest` | file-ingest | operators only: expose and secure it explicitly |
| `/actuator/reconciliation` | reconciliation | operators only; destructive operations are opt-in |

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
