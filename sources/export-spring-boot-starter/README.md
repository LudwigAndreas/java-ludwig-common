# export-spring-boot-starter

***English** · [Русский](README.ru.md)*

The platform's engine for tabular business reports: definitions declared as typed code and validated at startup,
parameterised and format-selected by the user at run time, sourced from the owning service's database through a
keyset-paginated stream, enriched with data other services own, and written as XLSX or CSV to a sink. Sized for
**1,000,000 rows × 25 columns, 8 of them enriched across up to 4 partner services**, inside a fixed heap budget,
surviving the death of the instance running it, and never silently producing a wrong or incomplete file.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | `audit-core`, `db-core`, `hot-reload`, `job-core`, `object-storage` (optional), `observability`, `odata-filter`, `outbox`, `rest-client`, `security`, `web-core` |
| Used by | `crud-service-example` |
| Surfaces | REST `/api/v1/reports/**`; Kafka/REST via outbox (`report.ready`); outbound REST (enrichment); filesystem or S3 sink |

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>export-spring-boot-starter</artifactId>
</dependency>
```

### Declare a report

```java
public static final ReportDefinition<OrderReportParameters, OrderRow> ORDERS = ReportDefinition
        .<OrderReportParameters, OrderRow>of("catalog.orders", OrderReportParameters.class, OrderRow.class)
        .titleKey("report.orders.title")
        .source(orderRowSource)
        .stage(CUSTOMER_STAGE)
        .column(ORDER_NUMBER)
        .column(CUSTOMER_NAME)
        .column(TOTAL)
        .allowedFormats(Set.of(StandardReportFormats.XLSX, StandardReportFormats.CSV))
        .defaultFormat(StandardReportFormats.XLSX)
        .maxRows(2_000_000)
        .build();
```

Contribute it through a `ReportDefinitionSource` bean. A user picks a definition (or a saved configuration),
supplies parameters, chooses a subset of the columns their roles allow and a format, and gets a file.

**Definitions are code, not rows.** Extractors are method references (renaming a field is a compile error, not an
empty column), parameters are records with Bean Validation, the source is a QueryDSL query over generated Q-types.
What varies per user (parameters, column subset, format) is data in a `SavedReport`, validated on write **and** on
load. Adding a report needs a deploy of the owning service; in exchange no report outlives the schema it reads and no
user-authored query reaches the database.

`crud-service-example` publishes `catalog.products` (`ProductRowSource`, `CatalogReports`, `SupplierEnricher`): a
keyset source projecting a record, a column restricted to `ROLE_CATALOG_ADMIN` (absent from the file for others), a
batched enrichment stage over a `@LudwigRestClient` with a localized marker for unknown keys and `DEGRADE` for an
outage, and data scope plus `@Filterable` filtering reused from the entity. `ProductReportIntegrationTest` drives it
end to end.

### The pipeline

```
ReportRequest (definition key, params, column subset, $filter, sort, locale, tz, format, options)
      |
      v
RowSource<R>            keyset-paginated QueryDSL stream; data-scope predicate applied;
      |                 ORDER BY total and ending in the primary key (never OFFSET)
      v
window(N) ─────────────────────────────────────────────────────┐
      |                                                         │ bounded memory:
      v                                                         │ window rows + their enrichment
EnrichmentExecutor      per window: distinct keys per stage,    │ + one style cache
  ├─ Batched            per-run cache, concurrent partner calls │
  ├─ PerItem            under a bound, join results back        │
  └─ Dimension                                                  │
      |                                                         │
      v  RenderedRow (typed CellValues) ────────────────────────┘
ReportWriter (XLSX | CSV | SPI)  -> temp file, streaming, bounded flush window
      |
      v
ReportSink -> run record updated -> download link and/or report.ready outbox event
```

| Decision | Why |
|---|---|
| Keyset pagination, never `OFFSET` | `OFFSET` reads and discards `n` rows per page and is unstable under concurrent writes (rows skipped silently). Order must be total; the source appends the primary key |
| Nothing fully materialised | Lazy `Stream<R>` closed on every exit; JPA sources clear the `EntityManager` between pages |
| Typed cells (`CellValue`: Text, Number, Money, Date, DateTime, Bool, Empty, Error) | Text cells sort dates alphabetically and break `SUM` |
| Absence ≠ failure | `MissingPolicy` for keys a partner does not know; `FailurePolicy` for a failing partner |
| Degradation is never silent | Marker in cells, degraded stages on the run and metadata sheet, `X-Report-Degraded`, a counter |
| Hidden columns are absent, not blank | A blank column discloses that data exists |
| Explicit backpressure | Reading/enriching on a producer thread, writing on the caller's; at most `handoff-queue-depth` windows between |
| Parallelism in enrichment | Per-stage fan-out capped by a semaphore over a shared pool |

### Enrichment

Per window and stage: distinct keys collected once, the per-run cache answers what it can, the rest go to the
partner. 2,000 rows referring to 200 customers = **one call**. The cache is load-bearing (roughly 100× fewer partner
calls at the design point) and caches **absence** too. It lives for one run (a report is a point-in-time statement).
Stages without dependencies fetch concurrently; merges are applied sequentially. A stage that degraded is not called
again in that run. `DimensionTooLargeException` (catalogue above `max-dimension-entries`) escapes the failure policy -
it is a configuration error.

| Situation | Row gets |
|---|---|
| Key resolved | The merged value |
| Row has no key | Nothing merged; the column's `NullPolicy` decides |
| Unknown key, `PLACEHOLDER` | That stage's cells carry the localized marker |
| Unknown key, `FAIL_ROW` | Row dropped and counted |
| Unknown key, `FAIL_REPORT` | Run fails, naming the stage (never the key) |
| Partner failed, `FAIL_REPORT` | Run fails |
| Partner failed, `DEGRADE` | Cells carry `unavailable`; stage listed on the run |

### Partner call identity

| | `SERVICE_ACCOUNT` (default) | `REQUESTER` |
|---|---|---|
| Partner sees | This service | The person who asked |
| Row-level scoping applied by | **The report** | The partner, on top |
| Works for deferred runs, subscriptions, reclaimed attempts | Yes | **No** (the token is never stored) |
| REST client `auth.type` | `oauth2-client-credentials` (or basic, api-key, bearer, mTLS) | `oauth2-token-relay` |

Default via `ludwig.export.enrichment.call-as`, override per stage:

```java
EnrichmentStage.<OrderRow, String, Customer>of("customer", customerEnricher)
        .keyExtractor(OrderRow::customerId)
        .merge(OrderRow::withCustomer)
        .restClient("customers")
        .callAs(CallIdentity.REQUESTER)
        .build();
```

The identity is applied by the named client's `auth.type`; startup refuses `REQUESTER` with a non-relay client (the
partner would answer with this service's view) and `SERVICE_ACCOUNT` with a relay client (deferred runs would fail).
A `REQUESTER` run that would be deferred is refused at request time with `identity-unavailable` (409); a deferred
attempt fails terminally with the same code. It never falls back to the service account.

### Web API (`ludwig.export.web.base-path`, default `/api/v1/reports`)

| Endpoint | Does |
|---|---|
| `POST /{definitionKey}/runs` | Request a report: **200** if run on the request thread (terminal envelope), **202** with `Location` if queued |
| `GET /runs/{id}` | Status and progress; `Retry-After` while not finished |
| `GET /runs/{id}/outputs/{format}` | Download; re-checks the caller; `X-Report-Sha256` and `X-Report-Degraded` |
| `DELETE /runs/{id}` | Cooperative cancel; **202** |
| `GET /definitions` | What *this caller* may run, with *their* columns |

A run requested by someone else answers **404**, not 403. Responses carry web-core's `operation` envelope beside the
module's own fields (`status` and `operation.status` are projected from one row). States:
`PENDING RUNNING SUCCEEDED FAILED CANCELLED EXPIRED`. Cancellation is checked once per window.

### Saved configurations and subscriptions

- A **saved report** prefills parameters, narrows columns, picks a format; it can introduce nothing the definition
  does not declare. Validated on write and on load; a stale one fails loudly (and is hidden from listings).
- A **subscription** (cron, leased lock) runs as its `run_as` subject with authorities re-resolved at execution. A
  missed window is **skipped, never replayed**.

### `report.ready`

On success, in the same transaction as `SUCCEEDED`, the run publishes `report.ready` (`ReportReadyEvent`) through the
outbox - **a link, never an attachment**. Declared as an `OutboxEventTypes` bean.

### Sinks

| `sink.type` | Class | When |
|---|---|---|
| `filesystem` (default) | `FilesystemReportSink` | One instance with a mounted volume |
| `s3` | `S3ReportSink` | More than one replica (delegates to [object-storage](../object-storage-spring-boot-starter); add it and the AWS SDK) |
| a bean name | your `ReportSink` | |

Keys: `<key-prefix>/<run-id-prefix>/<run-id>/<file-name>`. `RetryingReportSink` wraps the selected sink; attempts are
`sink.store-attempts`. Purged outputs keep their row (`purged_at`).

### Formats

- **XLSX**: streaming POI with a style cache keyed by look (bounded to dozens of styles; a test writes 12,000 cells and
  asserts < 30 styles), typed cells, currency formats, frozen header and autofilter, totals row, metadata (provenance)
  sheet, sanitised and deduplicated sheet names, continuation sheets (`Orders continued (2)`, header repeated) at the
  1,048,576-row ceiling, optional template (name on the definition, resolved under `template-directory` then the
  classpath, re-read every run).
- **CSV**: `excel` profile (UTF-8 + BOM, locale list separator, CRLF) or `rfc4180`; formula sanitisation; no totals
  row (declared in `FormatCapabilities`).

### Extension points

| Seam | Shape |
|---|---|
| `RowSource<P, R>` | `open`, `sortableColumns`, `estimateRows`; keyset, lazy, closed by the engine |
| `Enricher<K, V>` | **sealed**: `Batched`, `PerItem`, `Dimension` |
| `CellValue` | **sealed**: eight shapes |
| `ReportFormat` / `ReportWriterFactory` / `ReportWriter` | **open** - a new format is a bean (PDF/ODS not shipped) |
| `ReportSink` | store / open / delete |
| `XlsxTemplateSource` | name → workbook bytes |
| `CellRenderer<V>` | value + `RenderContext` → `CellValue` |
| `ExportMetrics` | metrics binding (no-op is a real bean) |
| `MessageKeyValidator` | how the registry checks bundles |

Instants render in the caller's zone (web-core preferences, carried into `RenderContext`); an explicit `timeZone` in
the request wins.

## Configuration

Memory peak ≈ `window-size` rows + their enriched values + `handoff-queue-depth` × `window-size` rows + the writer's
flush window + the enrichment cache. **Tune these together.**

| Property | Default | Meaning |
|---|---|---|
| `ludwig.export.enabled` | `true` | Master switch |
| `ludwig.export.instance` | job-core identity | Written to `claimed_by` |
| `ludwig.export.source-page-size` | `1000` | Rows per keyset page |
| `ludwig.export.window-size` | `2000` | Rows enriched/written as one unit; memory bound and cancellation latency |
| `ludwig.export.handoff-queue-depth` | `4` | Windows between enrichment and writing (backpressure) |
| `ludwig.export.sync-threshold-rows` | `5000` | Estimates at or below run on the request thread; `0` defers all |
| `ludwig.export.wall-clock-budget` | `30m` | Max run time |
| `ludwig.export.max-rows-per-run` | `5000000` | Estate ceiling (lower of this and the definition's `maxRows`) |
| `ludwig.export.enrichment.batch-size` | `200` | Keys per batched call |
| `ludwig.export.enrichment.concurrency` | `4` | In-flight calls per stage per window |
| `ludwig.export.enrichment.cache-enabled` / `cache-size` | `true` / `50000` | Per-run cache |
| `ludwig.export.enrichment.max-dimension-entries` | `100000` | Dimension catalogue ceiling |
| `ludwig.export.enrichment.call-as` | `SERVICE_ACCOUNT` | Default partner identity |
| `ludwig.export.enrichment.missing` / `failure` | `PLACEHOLDER` / `FAIL_REPORT` | Default policies |
| `ludwig.export.temp.directory` | JVM temp | Partial files; **set it in containers** |
| `ludwig.export.temp.orphan-age` / `sweep-on-startup` | `6h` / `true` | Orphan sweep |
| `ludwig.export.formats.enabled` | all | Estate allow-list of format ids |
| `ludwig.export.formats.xlsx.flush-window` | `500` | Rows in memory per sheet |
| `ludwig.export.formats.xlsx.template-directory` | none | Template workbooks |
| `ludwig.export.formats.xlsx.freeze-header` / `metadata-sheet` | `true` / `true` | |
| `ludwig.export.formats.csv.profile` / `allow-request-override` | `excel` / `true` | |
| `ludwig.export.sink.type` | `filesystem` | `filesystem`, `s3`, or a bean name |
| `ludwig.export.sink.directory` | `${java.io.tmpdir}/ludwig-export` | Filesystem root |
| `ludwig.export.sink.bucket` / `key-prefix` | none | **Bucket required for `s3`** |
| `ludwig.export.sink.retention` | `7d` | Download window |
| `ludwig.export.sink.purge-interval` | `1h` | |
| `ludwig.export.sink.store-attempts` | `3` | |
| `ludwig.export.sink.download-link-ttl` | `15m` | |
| `ludwig.export.poller.enabled` | `true` | Execute deferred runs on this instance |
| `ludwig.export.poller.interval` | `5s` | |
| `ludwig.export.poller.claim-batch-size` | `1` | Runs claimed per poll |
| `ludwig.export.poller.concurrency` | `2` | Concurrent runs per instance (each holds its own window budget) |
| `ludwig.export.poller.lease-duration` / `heartbeat-interval` | `5m` / `1m` | |
| `ludwig.export.poller.drain-timeout` | `30s` | |
| `ludwig.export.poller.max-attempts` | `3` | |
| `ludwig.export.poller.retry-initial-delay` / `retry-max-delay` | `30s` / `10m` | |
| `ludwig.export.quota.concurrent-runs-per-user` | `3` | |
| `ludwig.export.quota.daily-runs-per-user` | `100` | Rolling day |
| `ludwig.export.liquibase.enabled` | `true` | |
| `ludwig.export.metrics.enabled` | `true` | |
| `ludwig.export.web.enabled` / `base-path` / `retry-after` | `true` / `/api/v1/reports` / `2s` | |

**Startup refuses** (all at once):

| Registry (definitions) | Configuration (`ExportConfigurationValidator`) |
|---|---|
| Duplicate keys; a column whose `requiredStage` is undeclared; a default sort the source cannot order by; sortable/filterable columns the definition lacks; a parameter type that is neither a record nor no-arg; an allowed format without a writer; multi-sheet with a flat format under `REJECT`; `maxRows` above a format ceiling without rollover; a header/title/placeholder key missing in `en` or `ru`; an invalid authority; a stage depending on an undeclared stage or itself | `source-page-size` > `window-size`; `enrichment.batch-size` > `window-size`; `cache-size` < `batch-size`; `sync-threshold-rows` > `max-rows-per-run`; `lease-duration` < 2 × `heartbeat-interval`; `poller.interval` ≥ `lease-duration`; `drain-timeout` ≥ `lease-duration`; `download-link-ttl` > `retention`; `purge-interval` > `retention`; unknown `sink.type`; `s3` without `bucket`; unusable `temp.directory`; `formats.enabled` naming an unknown format or disabling all of a definition's formats; a stage naming an unconfigured REST client, or concurrency above its pool; identity/auth mismatches (see above) |

## Deployment

- **Schema** (`db/changelog/export/export-changelog.xml`, own `SpringLiquibase` bean): `export_report_run` (partial
  claim index `(next_attempt_at, id) WHERE status = 'PENDING'`, reclaim index `lease_until WHERE status = 'RUNNING'`),
  `export_report_output` (purge index `expires_at WHERE purged_at IS NULL`), `export_saved_report`,
  `export_report_subscription`.
- **Temp directory** on a writable volume sized for concurrent runs × formats.
- **Sink**: a persistent volume (filesystem, single replica) or a bucket (`s3`, any number of replicas; add
  object-storage and the AWS SDK).
- **Partners**: a `@LudwigRestClient` per enrichment stage with pool ≥ stage concurrency.
- **Outbox route** for `report.ready` if consumers need it.
- **Heap**: the measured design point ran in `-Xmx512m`; size above it per concurrent run.

## Observability

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `ludwig.export.runs` | timer | `definition`, `outcome` | Run duration by outcome |
| `ludwig.export.rows.written` | counter | `definition` | Rows written |
| `ludwig.export.output.bytes` | counter | `definition`, `format` | Bytes produced |
| `ludwig.export.enrichment.calls` | timer | `definition`, `stage`, `outcome` | Partner calls |
| `ludwig.export.enrichment.cache.hits` / `.misses` | counter | `definition`, `stage` | Per-run cache effectiveness |
| `ludwig.export.enrichment.keys.missing` | counter | `definition`, `stage` | Keys the partner did not know |
| `ludwig.export.enrichment.degraded` | counter | `definition`, `stage` | Stages degraded |
| `ludwig.export.rows.dropped` | counter | `definition`, `stage` | Rows dropped by `FAIL_ROW` |
| `ludwig.export.retention.reclaimed.bytes` | counter | | Bytes purged |

Tags are bounded (definition key, format, stage); parameters never are.

**Logs** (`INFO`): definitions registered, configuration validated, run summary (rows, dropped, outputs, duration),
retries, sheet rollover, reclaimed runs, purge and sweep results, subscription submissions. `WARN`: lost lease,
degraded stage, store retries, failed runs, stale saved report, template problems, `report.ready` announcement
failure. `ERROR`: unhandled run failure, subscription submission failure, a report scoped to a resource with no
`DataScopeMapping`.

**Recommended alerts:**

| Condition | Why |
|---|---|
| `rate(ludwig_export_runs_seconds_count{outcome="FAILED"}[15m]) > 0` sustained | Runs failing |
| `increase(ludwig_export_enrichment_degraded_total[15m]) > 0` | A partner is down; files are partial |
| `ludwig_export_runs_seconds` p95 near `wall-clock-budget` | Runs about to time out |
| Log `Lost the lease on report run` | Lease too short or instance starved |

**Known gaps:** no gauge for queued (`PENDING`) runs or their age, and no metric for subscription submissions; a
stalled poller is visible only through the SQL in the runbook.

## Audit

Category `export`; every lifecycle transition emits an `ExportAuditEvent`:

| Field | Value |
|---|---|
| action | `run.<status>` (`run.pending`, `run.running`, `run.succeeded`, `run.failed`, `run.cancelled`, `run.expired`) |
| actor | the requester (or the subscription's `run_as`), `system` for system transitions |
| resource | `report-run` / run id / definition key |
| outcome | `SUCCESS`, `FAILURE`, or `PARTIAL` when an enrichment stage degraded |
| attributes | definition, format, row counts, degraded stages, failure code - PII columns redacted |

Reports are the widest bulk read in the estate; the trail is always on. `settings` and `export` are the default
categories shipped to a SIEM by the audit starter's outbox sink.

## Runbook

**Runs pile up in `PENDING`.** `SELECT status, count(*) FROM export_report_run GROUP BY status;` then
`SELECT id, attempts, next_attempt_at, failure_code FROM export_report_run WHERE status = 'PENDING' ORDER BY next_attempt_at LIMIT 20;`

| What you see | Meaning | What to do |
|---|---|---|
| `attempts = 0`, `next_attempt_at` in the past, nothing `RUNNING` | No poller is claiming | Check `ludwig.export.poller.enabled` on the instances and their datasource |
| `attempts = 0`, some `RUNNING`, draining slowly | Queue longer than throughput | Add instances (safe: `SKIP LOCKED`); raise `poller.concurrency` only after checking heap |
| `attempts` climbing, `failure_code` set | Failing and retrying | Read the code (= what the caller sees). Cancel runs with configuration/parameter faults |
| `next_attempt_at` far ahead | Backoff grew | Expected; ceiling `retry-max-delay` |

Do not delete `PENDING` rows; cancel them (`DELETE /api/v1/reports/runs/{id}`).

**Runs stuck in `RUNNING`.** `SELECT id, claimed_by, heartbeat_at, lease_until, now() FROM export_report_run WHERE status = 'RUNNING';`

| What you see | Meaning | What to do |
|---|---|---|
| `lease_until` ahead, `heartbeat_at` recent | Working | Nothing (a million rows take minutes) |
| `lease_until` in the past | Owner died | Next poll reclaims it (`Reclaimed ... report run(s)` at `INFO`); if not, no poller runs |
| `heartbeat_at` stale, lease ahead | Heartbeat starved | Thread dump; the module's scheduler is saturated |
| Running far past `wall-clock-budget` | Should be impossible | Thread dump, then cancel; report a defect |

A retry restarts from scratch (a run is idempotent on its request hash).

**A partner degraded.** Look at `ludwig_export_enrichment_degraded_total` and `degraded_stages` on recent runs.

| What you see | Meaning | What to do |
|---|---|---|
| `degraded` climbing, runs `SUCCEEDED` | Stage policy `DEGRADE` | Partner problem; the rest of the file is correct |
| Runs `FAILED` with `enrichment-failed` | Stage policy `FAIL_REPORT` | Partner problem; do not change the policy to silence the alert |
| `keys.missing` climbing without `degraded` | Partner healthy, keys unknown | Data question upstream |
| One stage dominates run time | Bounded by the client's pool | Raise the partner client's `max-per-route` **and** the stage's `concurrency` together |

**Temp directory filling.** `du -sh` on `ludwig.export.temp.directory`.

| What you see | Meaning | What to do |
|---|---|---|
| Recent `ludwig-export-*` files | Runs in flight | Nothing |
| Files older than `orphan-age` accumulating | Sweep not running or cannot delete | Check the startup sweep log and file ownership (files are `rw-------`) |
| Growth with no runs | Another process writes there | The module only creates `ludwig-export-*` |

A growing **sink** means the retention purge is not running: check its `RunLock` in `job_run_lock` and that
`purge-interval` < `retention`.

**Nobody can download a produced file.** `SELECT expires_at, purged_at, sha256 FROM export_report_output WHERE run_id = '<id>';`

| What you see | Meaning |
|---|---|
| `purged_at` set | Retention expired: `output-expired` |
| `expires_at` past, `purged_at` null | Expired, not yet swept; refused |
| 404 instead of 403 | Caller is not the requester and not an admin (intentional) |
| `X-Report-Sha256` mismatch with a recipient's copy | File replaced or corrupted in transit |

Problem codes (`ludwig.export.error.*`): `unknown-definition` 404, `unknown-format` / `format-not-allowed` /
`format-disabled` / `unknown-column` / `unsortable-column` / `parameter-invalid` / `parameters-invalid` /
`not-filterable` / `invalid-format-option` / `unknown-format-option` / `required-value-missing` /
`multi-sheet-unsupported` 400, `column-forbidden` / `report-forbidden` 403, `identity-unavailable` 409,
`quota-exceeded` 429, run failures `row-limit-exceeded`, `time-budget-exceeded`, `access-revoked`,
`enrichment-failed`, `enrichment-key-missing`, `sink-unavailable`, `write-failed`; downloads `output-not-ready`,
`output-expired`; `saved-report-stale`.

## Testing

Unit and Testcontainers integration tests (PostgreSQL) for the registry, validator, engine, both writers,
enrichment, persistence and run lifecycle, security integration, REST API, saved reports and subscriptions, plus
ArchUnit boundary rules. `crud-service-example` runs a report end to end against WireMock.

**Performance baseline** (`ReportLoadTest`, tagged and excluded from the default build; command in the module POM):
1,000,000 rows × 25 columns, 8 enriched across 3 stages, XLSX **and** CSV in one pass, `-Xmx512m`, enrichment
against in-process stubs, Apple Silicon / OpenJDK 26.0.2:

| | |
|---|---|
| Elapsed | **21.7 s** (47,619 rows/s) |
| Peak heap (sampled) | 168 MiB |
| Retained after run | 21 MiB (asserted < 128 MiB) |
| Full collections | 5 |
| XLSX / CSV | 93.7 MB / 284.2 MB |

Completion on a 512 MiB heap is the memory assertion (the test refuses heaps above 640 MiB). Real partner latency
dominates in production.

## Design decisions

- **Code-declared definitions**; user-varying parts are validated data.
- **Re-resolve authorities when a deferred run starts**: revocation applies to reports too.
- **Requester-scoped stages never fall back** to the service account.
- **Missed subscription windows are skipped**, not replayed (no storm after an outage).
- **Links, not attachments** in `report.ready`.
- **Templates are re-read per run**, not watched: a run reads once and lasts minutes.
- **No advice of its own**: all failures are `LocalizedException`s rendered by web-core.
