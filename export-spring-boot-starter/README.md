# export-spring-boot-starter

The platform's engine for tabular business reports: declared in code, parameterised and
format-selected by the user at run time, sourced from the owning service's database, enriched with
data other microservices own, and written as XLSX or CSV to an external sink.

Sized for **1,000,000 rows x 25 columns, of which up to 8 come from enrichment across up to 4
partner services**, inside a fixed heap budget, surviving the death of the instance running it, and
never silently producing a wrong or incomplete file.

> **Delivery status. All seven phases listed in [Roadmap](#roadmap) are complete and green.** The
> public API, the startup-validating registry, the configuration tree and its cross-field validator,
> the filesystem and object-storage sinks, the autoconfiguration, the streaming engine, both shipped writers (CSV and
> streaming XLSX), the cross-service enrichment executor, the persistence and run lifecycle on
> `job-core`, the security and `odata-filter` integrations, the REST API, saved configurations, cron
> subscriptions and the `report.ready` outbox event - **184 tests in this module**, 20 of them
> Testcontainers integration tests against real Postgres, plus nine ArchUnit boundary rules and a
> tagged load test excluded from the default build. A working report is wired into
> `crud-service-example` and exercised by five integration tests there, against a WireMock partner.
>
> Nothing in this README describes behaviour that is not in the module, and every performance number
> in it is measured - see [Performance baseline](#performance-baseline) for the
> figures and the machine they came from. What was deliberately left out is listed in
> [Roadmap](#roadmap).

---

## What it is

A service declares a report as a typed constant:

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

and contributes it through a `ReportDefinitionSource` bean. A user then picks a saved configuration,
supplies parameter values, chooses a subset of the columns their roles allow, chooses a format, and
gets a file.

### Why definitions are code and not rows

This is the decision the module is built around, and it is the same one `user-settings` takes for
its setting definitions.

The obvious way to make a reporting engine reusable across services is to let each one declare its
reports as data - a table of report names, column names and SQL - administered through a UI. That
is precisely how a reporting module becomes a second, untested query layer with no type checking,
no refactoring support, and a SQL injection surface that an administrator can edit. Every column is
a string, every extractor is an expression nothing compiles, and "which reports read this table" is
unanswerable.

Declaring them as constants moves all of that to the compiler. A column's extractor is a method
reference, so renaming a field on the row type is a compile error rather than an empty column; the
parameter type is a record with Bean Validation on it; the source is a QueryDSL query checked
against the generated Q-types.

What stays data is the part that genuinely varies per user: which parameters, which columns of the
allowed set, which format - a `SavedReport` row, validated against the definition on write **and**
on load.

The cost is honest: adding a report requires a deploy of the service that owns the data. In
exchange, no report can outlive the schema it reads, and no user-authored report can put an
unbounded query into a production database.

### The worked example, wired into a real service

`crud-service-example` publishes one: `catalog.products`, a product-catalogue extract over
`ProductEntity`. It is the shortest way to see what a definition looks like against a real schema,
and it deliberately exercises the four things the next report in an estate will need:

| What it shows | Where |
|---|---|
| A keyset-paginated QueryDSL source over generated Q-types, projecting a record rather than streaming entities | `service/report/ProductRowSource.java` |
| A column restricted to `ROLE_CATALOG_ADMIN`, **absent from the file** for anyone else | `CatalogReports.COLUMN_SUPPLIER_COST` |
| A batched enrichment stage against a named `@LudwigRestClient`, with a localized marker for a supplier the directory does not know and `DEGRADE` for a directory that is down | `SupplierEnricher`, `SupplierDirectoryApi` |
| Row-level scope taken from the service's existing `product` data-scope mapping, and filtering delegated to `ProductEntity`'s own `@Filterable` annotations rather than restated | `CatalogReports.products()` |

`ProductReportIntegrationTest` there drives it end to end - HTTP request, real PostgreSQL, enrichment
over real HTTP against a WireMock directory, a file written to a sink and read back with POI - and
asserts each of the four, plus that enrichment is batched (one partner call for thirty products, not
thirty) and that XLSX cells are typed.

---

## The pipeline

```
ReportRequest (definition key, params, column subset, $filter, sort, locale, tz, format, options)
      |
      v
RowSource<R>            keyset-paginated QueryDSL stream; data-scope predicate applied;
      |                 ORDER BY must be total and end in the primary key
      |  Stream<R>      (never OFFSET paging - it degrades quadratically at 1M rows)
      v
window(N)  ------------------------------------------------------+
      |                                                          |
      v                                                          |  bounded memory:
EnrichmentExecutor      per window: collect distinct keys per     |  window rows +
  |- Batched            stage, consult the per-run cache, call    |  their enrichment
  |- PerItem            partners concurrently under a bound,      |  + one style cache
  +- Dimension          join results back onto the window         |
      |                                                          |
      v  RenderedRow (typed CellValues, not strings)  ------------+
      |
      v
ReportWriter (XLSX | CSV | SPI)  -> temp file, streaming, bounded flush window
      |
      v
ReportSink -> run record updated -> signed download URL and/or outbox event
```

### The decisions the shape encodes, and what each one rules out

**Keyset pagination, never `OFFSET`.** `OFFSET n` makes the database read and discard `n` rows on
every page, so walking a million rows in pages of a thousand costs on the order of five hundred
million discarded reads and the last pages are the slowest. Worse, `OFFSET` is not stable under
concurrent writes: a row inserted while the report runs shifts the window, and a row that existed
for the whole run is silently never written. The corollary is that the order has to be total, which
is why a `RowSource` appends its own primary key to whatever sort was requested.

**Nothing is ever fully materialised.** The source is a lazy `Stream<R>`; the engine closes it on
every exit path. A JPA-backed source clears the `EntityManager` between pages, because otherwise the
persistence context becomes the leak the streaming was meant to prevent. There is no `List<R> all =
...` anywhere in this module, including in its tests.

**A cell is a typed value, not a string.** `CellValue` is sealed over Text, Number, Money, Date,
DateTime, Bool, Empty and Error. The alternative - extractors producing text - yields a spreadsheet
in which dates sort alphabetically, amounts are left-aligned, and `SUM` over a column of numbers
returns zero, so the recipient retypes the file by hand.

**Absence and failure are different outcomes.** A partner returning 200 records for 205 keys has not
failed; it has told the engine something true about five of them. Conflating the two is the single
most common cause of runaway retry loops in this class of system. `MissingPolicy` covers the first,
`FailurePolicy` the second, and the interface Javadoc on `Enricher.Batched.fetchBatch` says so at
the place an implementer would otherwise be tempted to throw.

**Degradation is never silent.** Under `FailurePolicy.DEGRADE` the affected cells carry a localized
marker, the run record lists the degraded stages, the metadata sheet names them, the download
response reports `degraded=true`, and a counter is incremented. A blank cell is never an acceptable
sole signal, because a reader cannot distinguish it from a genuinely absent value.

**Column visibility is absence, not blanking.** A column the requester may not see is removed from
the header row as well as from the data rows. A blank column tells the reader the data exists and
they were not given it, which is itself disclosure.

**Backpressure is explicit.** Reading and enriching run on a producer thread; rendering and writing
run on the caller's. Between them is a queue of at most `handoff-queue-depth` finished windows,
which is both the overlap and the bound: the two halves wait on completely different things -
partner latency and disk - so running them at once is worth a thread, and a producer allowed to run
ahead without limit would hold the whole report in memory. With no pool available the pump reads on
the caller's thread instead, which loses the overlap and nothing else.

**The parallelism is upstream, in enrichment**, where a report actually waits - on partner calls,
not on bytes. Each stage's fan-out is capped by a semaphore over a shared pool rather than by a pool
of its own, so `concurrency` means what it says: how much of the partner's attention this stage may
take, not how much memory it is entitled to.

---

## What the registry refuses to start on

`ReportDefinitionRegistry` validates the whole estate before the context finishes starting, and
reports **every** problem in one message rather than one per restart. Each check is a mistake that
produces a *wrong report* rather than a failed one:

| Refused | Why it is worth a refused context |
|---|---|
| Two sources declaring the same key | Which one wins depends on bean instantiation order |
| A column whose `requiredStage` is not declared | The column is empty in every row, with nothing at runtime to say why |
| A default sort the source cannot order by | Keyset pages overlap and skip, so the file is missing rows and has others twice |
| A source declaring a sortable column the definition does not have | One of the two has drifted; which is unknowable at runtime |
| A filterable column the definition does not have | Same |
| A parameter type that is neither a record nor no-arg constructible | Discovered otherwise by the first user, as a 500 |
| An allowed format with no registered `ReportWriterFactory` | Discovered otherwise at the first request |
| Multi-sheet plus a flat format under `MultiSheetStrategy.REJECT` | Ends as a truncated file that opens |
| `maxRows` above a format's `maxRowsPerSheet` with no rollover | Same |
| A header, title or placeholder key missing from `en` **or** `ru` | The header renders as its own key, for exactly the users whose locale was forgotten |
| A syntactically invalid authority | Hides the column from everyone, permanently and silently |
| A stage depending on a stage nobody declared, or on itself | The dependency never resolves and every row reports not found |

`ExportConfigurationValidator` does the same for the configuration - the failures Bean Validation
cannot see because they are relationships rather than fields:

| Refused | Why |
|---|---|
| `source-page-size` > `window-size` | The page becomes the memory bound instead of the window |
| `enrichment.batch-size` > `window-size` | A batch can never be filled, so the setting has no effect |
| `enrichment.cache-size` < `enrichment.batch-size` | One batch evicts the previous one; the cache costs a lookup per key and returns nothing |
| `sync-threshold-rows` > `max-rows-per-run` | Every run big enough to defer would already have been refused |
| `poller.lease-duration` < 2x `poller.heartbeat-interval` | One slow renewal loses the lease mid-run and the report is produced twice |
| `poller.interval` >= `poller.lease-duration` | A reclaimed run waits a full poll before anything looks at it |
| `poller.drain-timeout` >= `poller.lease-duration` | Shutdown waits for a run another instance has already taken |
| `sink.download-link-ttl` > `sink.retention` | A link stays valid after the purge deleted the file behind it |
| `sink.purge-interval` > `sink.retention` | Outputs routinely outlive their retention |
| `sink.type` naming no registered sink | Discovered when the first report finishes and has nowhere to go |
| `sink.type` is `s3` with no `sink.bucket` | Same, one restart later |
| `temp.directory` absent, not a directory, or not writable | Every run fails at its first write |
| `formats.enabled` naming a format nothing writes | The name is silently ignored today and silently wrong later |
| A definition all of whose formats `formats.enabled` switches off | The report can never be produced |
| A stage naming a REST client that is not configured | The calls are made outside the platform's pools, timeouts and breakers |
| A stage whose concurrency exceeds its REST client's pool | The fan-out bound becomes an unbounded wait inside the pool |
| A `REQUESTER` stage whose REST client cannot relay a token | The partner answers with everything *this service* may see, and nothing in the file says the report was not scoped to the caller |
| A `SERVICE_ACCOUNT` stage whose REST client relays | Synchronous runs work and every deferred one fails, because a poller thread has no token |

---

## Configuration

Everything lives under `ludwig.export`. The defaults are a **set**, not five independent knobs: the
peak is roughly

```
  window-size rows
+ window-size x (enriched columns) values from the partners
+ handoff-queue-depth x window-size rows waiting to be written
+ the writer's flush window
+ the enrichment cache
```

so raising one without the others is the usual way a service that was comfortable at 100,000 rows
falls over at a million.

| Property | Default | What it decides |
|---|---|---|
| `ludwig.export.enabled` | `true` | Master switch for the module's autoconfiguration |
| `ludwig.export.instance` | `job-core` identity | Written into a run's `claimed_by` |
| `ludwig.export.source-page-size` | `1000` | Rows per keyset page; what the database materialises |
| `ludwig.export.window-size` | `2000` | Rows enriched and handed to the writer as one unit; the memory bound and the cancellation latency |
| `ludwig.export.handoff-queue-depth` | `4` | Windows that may sit between enrichment and writing; this is the backpressure |
| `ludwig.export.sync-threshold-rows` | `5000` | At or below this estimate, run on the request thread. `0` defers everything |
| `ludwig.export.wall-clock-budget` | `30m` | How long a run may take before it is failed |
| `ludwig.export.max-rows-per-run` | `5000000` | Estate-wide ceiling, applied on top of each definition's `maxRows`; the lower wins |
| `ludwig.export.enrichment.batch-size` | `200` | Keys per call for a batched stage |
| `ludwig.export.enrichment.concurrency` | `4` | Concurrent in-flight calls per stage per window |
| `ludwig.export.enrichment.cache-enabled` | `true` | Per-run value cache; load-bearing, not an optimisation |
| `ludwig.export.enrichment.cache-size` | `50000` | Cache entries per run across all stages |
| `ludwig.export.enrichment.max-dimension-entries` | `100000` | Catalogue ceiling above which a dimension stage fails the run |
| `ludwig.export.enrichment.call-as` | `SERVICE_ACCOUNT` | Whose credentials partner calls carry when a stage does not say; see [Partner call identity](#partner-call-identity) |
| `ludwig.export.enrichment.missing` | `PLACEHOLDER` | Default policy for a key the partner does not know |
| `ludwig.export.enrichment.failure` | `FAIL_REPORT` | Default policy for a partner that failed after its retries |
| `ludwig.export.temp.directory` | JVM temp | Where partial files are written; set it on any container |
| `ludwig.export.temp.orphan-age` | `6h` | How old an orphaned temp file must be before the sweep removes it |
| `ludwig.export.temp.sweep-on-startup` | `true` | Whether the sweep runs |
| `ludwig.export.formats.enabled` | *(all)* | Estate-level allowlist of format ids |
| `ludwig.export.formats.xlsx.flush-window` | `500` | Rows kept in memory per sheet before POI flushes |
| `ludwig.export.formats.xlsx.template-directory` | *(none)* | Watched directory of template workbooks |
| `ludwig.export.formats.xlsx.freeze-header` | `true` | Frozen header row and autofilter |
| `ludwig.export.formats.xlsx.metadata-sheet` | `true` | Whether the provenance sheet is written |
| `ludwig.export.formats.csv.profile` | `excel` | `excel` (UTF-8 + BOM, locale list separator, CRLF) or `rfc4180` |
| `ludwig.export.formats.csv.allow-request-override` | `true` | Whether a request may override the profile |
| `ludwig.export.sink.type` | `filesystem` | Which sink takes finished files: `filesystem`, `s3`, or the bean name of one a service contributed |
| `ludwig.export.sink.directory` | `${java.io.tmpdir}/ludwig-export` | Root for the filesystem sink |
| `ludwig.export.sink.bucket` | *(none)* | Bucket for the `s3` sink; **required** when `type` is `s3` |
| `ludwig.export.sink.key-prefix` | *(none)* | Key prefix within that bucket, so reports can share it with something else |
| `ludwig.export.sink.retention` | `7d` | How long an output stays downloadable |
| `ludwig.export.sink.purge-interval` | `1h` | How often the purge looks for expired outputs |
| `ludwig.export.sink.store-attempts` | `3` | Attempts to store a finished file before the run fails |
| `ludwig.export.sink.download-link-ttl` | `15m` | How long a download link stays valid |
| `ludwig.export.poller.enabled` | `true` | Whether this instance executes deferred runs |
| `ludwig.export.poller.interval` | `5s` | How often the poller looks for claimable runs |
| `ludwig.export.poller.claim-batch-size` | `1` | Runs claimed per poll; one, because a run holds a thread for minutes |
| `ludwig.export.poller.concurrency` | `2` | Runs this instance executes at once |
| `ludwig.export.poller.lease-duration` | `5m` | How long a claim is held before another instance may take it |
| `ludwig.export.poller.heartbeat-interval` | `1m` | How often the lease is renewed |
| `ludwig.export.poller.drain-timeout` | `30s` | How long shutdown waits for a run in flight |
| `ludwig.export.poller.max-attempts` | `3` | Attempts before a failed run stops being retried |
| `ludwig.export.poller.retry-initial-delay` | `30s` | First retry delay |
| `ludwig.export.poller.retry-max-delay` | `10m` | Retry delay ceiling |
| `ludwig.export.quota.concurrent-runs-per-user` | `3` | Runs one user may have PENDING or RUNNING at once |
| `ludwig.export.quota.daily-runs-per-user` | `100` | Runs one user may start in a rolling day |
| `ludwig.export.liquibase.enabled` | `true` | Whether this module applies its own changelog |
| `ludwig.export.metrics.enabled` | `true` | Whether Micrometer instrumentation is registered |
| `ludwig.export.web.enabled` | `true` | Whether the REST layer is registered |
| `ludwig.export.web.base-path` | `/api/v1/reports` | Base path for this module's controllers |

---

## Failure semantics

| Situation | Behaviour |
|---|---|
| Unknown definition key | Localized RFC 9457 404 carrying `definition` |
| Unknown format / format not allowed / format disabled | Localized 400 naming the valid values in `available` or `allowed` |
| Unknown column, forbidden column, unsortable column | Localized 400 (403 for forbidden) carrying `column` and `available` |
| Parameter validation failure | 400 with per-field violations, i18n |
| Filter references a non-filterable column | 400 via `odata-filter`'s policy, never a 500 |
| Row cap or wall-clock budget exceeded | Run `FAILED` carrying `limit`, `observed` and the `property` that set it; partial output deleted |
| A row routed to no declared sheet, or to one already closed | Run fails naming the sheet; see the contiguity rule on `SheetDefinition` |
| Partner does not know a key | `MissingPolicy`; localized placeholder by default, counted |
| Partner failing after its client's retries | `FailurePolicy`; `FAIL_REPORT` by default, `DEGRADE` visibly marked everywhere |
| Instance dies mid-run | Lease expires, run reclaimed, restarted from scratch, temp file swept |
| Requester lost access before a deferred run | Run fails with `ACCESS_REVOKED`; no file produced |
| A `REQUESTER` stage on a run that would be deferred | Refused at request time with `IDENTITY_UNAVAILABLE` (409); a deferred attempt fails terminally with the same code, never falling back to the service account |
| Sink unavailable | Retried across `store-attempts` with `job-core`'s backoff; after exhaustion the run is `FAILED` with `SINK_UNAVAILABLE` and the temp file is deleted |
| The requester's quota is exhausted | Localized 429 naming which quota and its ceiling |
| The same request submitted twice | The first run's id is returned, not a second run |
| Disk full while writing | Run `FAILED` with `WRITE_FAILED`; temp file deleted; no partial upload |

---

## The web API

| Endpoint | What it does |
|---|---|
| `POST /api/v1/reports/{definitionKey}/runs` | Requests a report. **200** if it ran on the request thread, **202** if it was queued — same body either way, so a client that always polls is correct and one that checks the status is faster |
| `GET /api/v1/reports/runs/{id}` | Status and progress |
| `GET /api/v1/reports/runs/{id}/outputs/{format}` | Downloads a produced file |
| `DELETE /api/v1/reports/runs/{id}` | Asks the run to stop; **202**, because the run may be on another instance and all this can truthfully report is that the request was recorded |
| `GET /api/v1/reports/definitions` | What *this caller* may run, with *their* columns |

**There is no `@RestControllerAdvice` in this module.** Every failure is a `LocalizedException` that
`web-core`'s single pipeline renders, so a report's 403 is shaped and translated like every other 403
in the service.

**The definitions listing is built per caller.** A list including reports they cannot run would be a
catalogue of what exists; a list including columns they cannot export would produce a 403 the moment
they used it.

**A run somebody else requested reports as 404, not 403.** A 403 confirms the run exists, which for a
resource addressed by an opaque id is the only thing an enumeration attempt could learn.

**The download re-checks the caller every time** — holding a run id is not authorisation — and
carries `X-Report-Sha256` so a recipient can verify months later that the bytes they still have are
the bytes the run produced, plus `X-Report-Degraded` so a partial report announces itself.

## Saved configurations and subscriptions

A saved report is an administrator's reusable configuration of a definition: prefilled parameters, a
narrowing of the allowed columns, a format. Nothing in it can introduce a query, a column or a format
the definition does not already declare — that is what keeps "definitions are code" honest while
still letting the per-organisation part be data.

**Validated on write and again on load.** The second is the one that matters: a definition changes at
deploy time and a configuration written against the old one does not. A saved report naming a removed
column fails loudly and names it, because the alternative is a file quietly narrower than the one the
same configuration produced last month. The *listing* hides a stale configuration rather than failing
the whole screen; every other path throws.

**A subscription's missed window is skipped, never replayed.** An instance down for a weekend comes
back and produces one report per subscription, not forty-eight. Replaying every window would turn a
routine outage into a storm against the database and every partner at once, on an instance whose
caches are still cold.

**A scheduled run executes as the subscription's `run_as` subject**, scoped by *their* authorities
re-resolved at execution time. A scheduled report running with the service's own entitlements would
be the one place in the estate producing a file nobody is authorised to see — and it would keep
producing it after they left.

## `report.ready`

On success, and in the same transaction as the `SUCCEEDED` status, the run publishes
`report.ready` through `outbox-spring-boot-starter` — **a link, never an attachment**. A 40 MB XLSX
on a message bus is a message half the consumers reject and the other half hand to a mail server that
will. The link also means the download goes through the same authorisation check as any other, and
that the expiry means something; an emailed copy is forever.

## Security

- The base query always carries the requester's data-scope predicate from `security`. Being a
  report is not an exemption.
- Column visibility is evaluated against the requester's authorities; a hidden column is **absent**
  from the file, not blank.
- **Whose credentials an enrichment call carries is configured, not decided here** - see
  [Partner call identity](#partner-call-identity).
- **A deferred run re-resolves authorities at execution time**, not from the snapshot taken when it
  was requested. `identity-projection` evicts the authority cache on change precisely so revocation
  takes effect in milliseconds; a reporting engine that ignored that would be the one path in the
  estate where a revoked permission still produced data, and the highest-volume one.
- PII columns are flagged in the definition and redacted in audit, logs, metric tags and problem
  documents. Metric tags stay bounded: definition key and format are tags, parameters are not.
- Every lifecycle transition emits an `ExportAuditEvent`. Reports are the most common
  data-exfiltration path in an enterprise estate; the trail is not optional, which is why the sink
  interface has no enabled flag of its own.

---

## Partner call identity

A stage's partner calls can go out under **this service's own credentials** or under **the
requester's relayed token**. Both are legitimate, they answer different questions, and one report may
need both, so the module offers the choice rather than making it.

| | `SERVICE_ACCOUNT` (default) | `REQUESTER` |
|---|---|---|
| Who the partner sees | This service | The person who asked |
| Who applies row-level scoping | **The report must** — its data scope, its key extractor, its `visibleFor` | The partner does, on top of whatever the report does |
| Works for a deferred run, a subscription, a reclaimed attempt | Yes | **No** — the token was never stored |
| REST client `auth.type` | `oauth2-client-credentials` (or basic, api-key, bearer, mTLS…) | `oauth2-token-relay` |
| Typical partner | A reference catalogue, a directory, a service this deployment has a provisioned integration with | A customer-facing service whose own authorization must not be bypassed |

Set the default once with `ludwig.export.enrichment.call-as`, and override it per stage:

```java
EnrichmentStage.<OrderRow, String, Customer>of("customer", customerEnricher)
        .keyExtractor(OrderRow::customerId)
        .merge(OrderRow::withCustomer)
        .restClient("customers")
        .callAs(CallIdentity.REQUESTER)
        .build();
```

**Per stage rather than per report**, because one report legitimately joins both kinds of partner: a
currency table this service integrates with under its own credentials, and an orders service whose
per-customer scoping must be respected. Forcing one identity on the whole report would make the
second inexpressible without splitting the report in two.

### What stops the two halves from disagreeing

The identity is *applied* by the named `@LudwigRestClient`, through its `auth.type`. Nothing in this
module sends a header. Declaring it on the stage as well is what makes the two checkable, and the
startup validator refuses these pairs:

| Stage says | Client is configured | Why it is refused |
|---|---|---|
| `REQUESTER` | `oauth2-client-credentials`, `basic`, `api-key`, `bearer`, `none` | The partner would answer with everything *this service* may see. The file would contain more than the requester is entitled to, and nothing in it would say so. |
| `SERVICE_ACCOUNT` | `oauth2-token-relay` | Synchronous runs would work; every deferred run would fail, because a poller thread has no token to relay. |

Anything else — mutual TLS, a custom authenticator, an auth type registered later — is left alone.
The module refuses only pairs it can actually judge; failing a deployment over a check that could not
be performed would punish somebody for extending the platform correctly.

### The deferred-run constraint, and why it is not a fallback

A `REQUESTER` stage needs a token that exists only while the request thread does. The requester's
access token is deliberately never stored — a stored bearer token is a long-lived credential at rest —
so a deferred attempt has none.

The module therefore **refuses**, in two places, and never falls back:

- **At request time**, when the row estimate says the run would be queued: the caller gets
  `ludwig.export.error.identity-unavailable` (409) while they are still listening, with a message
  telling them to narrow the request or to have the report given its own access.
- **At plan time** on a deferred attempt, for the cases an estimate cannot cover — a subscription, a
  saved configuration whose data grew, an attempt reclaimed after an instance died. It becomes the
  run's terminal failure code rather than a retryable one: waiting will not produce a request thread.

Falling back to the service account would produce a file scoped to this service rather than to the
person, which is precisely the file `REQUESTER` was chosen to prevent — and it would look complete.

---

## Extension points

| Seam | Shape | Notes |
|---|---|---|
| `RowSource<P, R>` | open, `sortableColumns`, `estimateRows` | Keyset-paginated, lazy, closed by the engine |
| `XlsxTemplateSource` | name -> workbook bytes | What a template name may resolve to; the shipped one bounds it to a directory and the classpath |
| `ExportMetrics` | seven recording methods | Where run, call, cache and degradation counts go; the no-op is a real bean, so the engine has no null checks to forget |
| `Enricher<K, V>` | **sealed**: Batched, PerItem, Dimension | Extension is by choosing a shape, not inventing one |
| `CellValue` | **sealed**: eight shapes | Every writer switches over it exhaustively |
| `ReportFormat` / `ReportWriterFactory` / `ReportWriter` | **open** | The intended extension point; a new format is a bean, not a change here |
| `ReportSink` | store / open / delete | Two shipped implementations, filesystem and object storage; the second arrived without an engine change, which is what the three methods were for |
| `ExportAuditSink` | one method | Deliberately write-only |
| `CellRenderer<V>` | value + `RenderContext` -> `CellValue` | Escape hatch for a value no format maps to |
| `MessageKeyValidator` | key + locale -> boolean | How the registry checks bundles |

PDF and ODS writers are **deliberately out of scope**: the seam exists so they arrive without
reopening the engine. The object-storage sink was out of scope on the same terms and has since
arrived, as `S3ReportSink` — and it did arrive without an engine change, which is the evidence that
the seam is real rather than asserted.

---

## Sinks

Two are shipped, and both are real.

| `sink.type` | Class | When |
|---|---|---|
| `filesystem` (default) | `FilesystemReportSink` | One instance writing to a mounted volume |
| `s3` | `S3ReportSink` | More than one replica |

The filesystem sink is not a placeholder — a single-instance deployment writing to a volume is an
ordinary way to run this, and it is what the module's own integration tests use. What it is not is a
deployment where any instance can serve any download: a run executed on instance A leaves its file on
instance A, so a load balancer in front of two instances serves half the download requests a 404.
That is the situation `S3ReportSink` exists for, and the only one.

`S3ReportSink` carries **no bucket client of its own**. It delegates to
[`object-storage-spring-boot-starter`](../object-storage-spring-boot-starter), which is the platform's
one `ObjectStore` — a second client written here would be the `JdbcRunLock` mistake happening again,
with export and file ingest disagreeing about retries, about whether deleting an absent object is a
success, and about how a location is spelled. That starter is an **optional** dependency: a
single-instance deployment should not resolve an AWS SDK to produce a spreadsheet, so a service that
sets `sink.type: s3` declares it, and the SDK, explicitly.

Keys are `<key-prefix>/<run-id-prefix>/<run-id>/<file-name>` — the same shape the filesystem sink
produces. The two-character fan-out segment buys nothing in S3, which has no directories; it is kept
so that an estate migrating between the two can copy the tree across unchanged and an operator reading
a stored uri does not have to know which sink wrote it.

`RetryingReportSink` wraps whichever is selected. Neither implementation retries internally, and
neither does the storage module underneath — its SDK retry policy is configured down to one mechanical
retry — so the attempts a run is allowed remain `sink.store-attempts` rather than that number
multiplied by whatever the SDK was doing.

---

## Roadmap

| Phase | Contents | Status |
|---|---|---|
| 1 | POM, `api` package, registry with startup validation, properties + validator, filesystem sink, autoconfiguration, README | **done** |
| 2 | Engine: `RowSource` walking, windowing, sheet routing, totals folding, row cap, wall-clock budget, cancellation, temp-file lifecycle and orphan sweep, multi-format single pass, CSV writer with both profiles and formula sanitisation, end-to-end into a sink | **done** |
| 3 | XLSX writer: style cache keyed by look, typed cells, currency number formats, frozen header and autofilter, declared widths, totals row, metadata sheet, sheet-name sanitising and deduplication, continuation sheets at the row ceiling, template mode | **done** |
| 4 | Enrichment executor: the three shapes, per-run Caffeine cache with negative caching, dependency levels with concurrent fetches, missing/failure policies, degraded-stage marking, bounded hand-off between the two halves, `ExportMetrics` with Micrometer and no-op implementations | **done** |
| 5 | Persistence (four tables, Liquibase changelog, claim/reclaim/purge indexes), run lifecycle on `job-core` - claim, lease, heartbeat, backoff retry, cancel, reclaim - per-user quotas, idempotency key, retention purge under a leased lock, sink store retry, audit sink, Testcontainers integration suite | **done** |
| 6 | Security integration (authority re-resolution, data-scope predicate, fail-closed), `odata-filter`-backed filter parsing, the execution planner and its refusals, the REST API, saved configurations validated on write and load, cron subscriptions under a leased lock, the `report.ready` outbox event | **done** |
| 7 | Full test suite including the tagged 1M-row load test with measured numbers, this module's own ArchUnit boundary rules, the subscription scheduler's test, and a working report wired into `crud-service-example` and exercised end to end against a WireMock partner | **done** |

### Enrichment: collect, consult, call, join

Per window, per stage: distinct keys are collected once, the cache answers what it can, and only
what is left reaches a partner. A window of 2,000 rows referring to 200 customers is **one call, not
2,000**.

**The cache is load-bearing, not an optimisation.** At the design point a key recurs across windows,
and the difference between caching and not is roughly two orders of magnitude in partner calls - a
report that should make 4,000 calls makes 400,000 without it, against a service sized for
interactive traffic. **Absence is cached too**: a key the partner does not know is stored as a
sentinel, because otherwise every window asks again and a report over data with a few thousand
orphaned references spends its whole partner budget re-learning the same nothing.

The cache is scoped to one run and bounded by entry count. Per run because a report is a
point-in-time statement, and a cache that outlived a run would make the next one silently a mixture
of two.

**Stages are ordered into levels.** Stages with no declared dependency form a level whose *fetches*
go out concurrently; their *merges* are then applied one after another on the calling thread. The
fetches are what a report waits on; the merges are pure functions over the window that take
microseconds, and running them concurrently would mean two stages producing two independently
derived versions of the same row with something having to reconcile them. There is nothing to
reconcile this way. A dependency cycle is refused at startup by the registry.

**A degraded stage is not called again.** Once a stage has failed under `DEGRADE`, every later window
marks its cells without calling: the partner has already had every retry its REST client allows, and
continuing for another 500 windows turns one partner's outage into this service's.

**A configuration error is not a partner failure.** `DimensionTooLargeException` deliberately escapes
the failure policy - the partner answered, and the stage declared a ceiling its catalogue does not
fit under. Letting `DEGRADE` swallow it would ship a file full of "unavailable" markers whose real
cause is a number somebody has to change.

| Situation | What the row gets |
|---|---|
| Key resolved | The merged value |
| Row has no key at all | Nothing merged; the column's own `NullPolicy` decides |
| Partner does not know the key, `PLACEHOLDER` | That stage's cells carry the localized marker; other columns are untouched |
| Partner does not know the key, `FAIL_ROW` | The row is dropped and counted |
| Partner does not know the key, `FAIL_REPORT` | The run fails, naming the stage - never the key |
| Partner failed, `FAIL_REPORT` | The run fails |
| Partner failed, `DEGRADE` | That stage's cells carry `unavailable`; the stage is listed on the run |

### The run lifecycle

A deferred run is a row claimed with `job-core`'s `FOR UPDATE SKIP LOCKED` statement, leased, and
heartbeated while it executes. The pieces that matter:

**The claim takes the lease and counts the attempt in one statement.** Counting at claim time rather
than at completion is what stops a run that kills the instance executing it from being reclaimed
forever: it exhausts its budget and fails with something an operator can read.

**The poller claims only what it can start.** A permit is taken *before* the claim, not after — a
report holds a thread for minutes, so claiming a batch and then finding capacity for one of them
would leave the rest marked `RUNNING` and idle until their leases expired. This is why
`claim-batch-size` defaults to 1 here where the outbox's is larger.

**Reclaim runs before claim, in the same tick**, so a crashed instance's report becomes claimable
immediately rather than a poll interval later.

**A lease renews only for the instance that holds it.** The owner is in the predicate, not merely
written: a renewal that succeeded regardless of holder would let a reclaimed instance keep extending
a lease somebody else owns, and two instances would each believe they were entitled to write the
file. A renewal that comes back false stops the run at its next window.

**Cancellation is a column, not a status.** The run stays `RUNNING` until the instance executing it
notices; a status flipped by a second instance would be resurrected by the first one's next write.

**A deferred run re-plans when it starts**, from the requester's authorities as they are *now*. That
is the security control, not an optimisation — see [Security](#security).

Two tables: `export_report_run` and `export_report_output`, with a partial claim index on
`(next_attempt_at, id) WHERE status = 'PENDING'` whose column order is exactly the due predicate and
`ORDER BY`, a separate partial reclaim index on `lease_until WHERE status = 'RUNNING'`, and a partial
purge index on `expires_at WHERE purged_at IS NULL`. The schema ships as a Liquibase changelog
registered as its own `SpringLiquibase` bean, and the integration tests run with
`ddl-auto=validate`, so an entity column forgotten in the changelog fails there rather than on a
deployment.

**The output row outlives the bytes.** The purge marks `purged_at` rather than deleting: an audit
trail that shrank to exactly the retention window would be the opposite of a trail.

### The XLSX style cache

A workbook may hold about 64,000 cell styles, and an exporter that calls `createCellStyle()` per
cell reaches that ceiling around row 10,000 of a four-column report - on real data, never on the
fifty-row fixture it was developed against. It is the single most common way an XLSX exporter fails
after being declared finished.

`XlsxStyles` makes it structurally impossible rather than a matter of remembering: styles are
reachable only through a cache lookup keyed by `(CellFormat, emphasis, per-row currency)`, and there
is no method that would create one per cell. The bound is columns x 3 x currencies, which for any
real definition is dozens. A test writes 12,000 cells and asserts the workbook came out with fewer
than 30 styles.

The per-row-currency case is the one that costs anything, and the cost is stated on
`CellFormat.moneyPerRow`: one style per currency rather than one per column.

### Sheet ceilings and rollover

A sheet holds 1,048,576 rows including its header. Past that the writer opens a continuation sheet
named `Orders continued (2)`, `Orders continued (3)` and so on, and **repeats the header on each** -
a second tab of unlabelled columns is unusable on its own, and a spreadsheet has no way to say "see
the previous tab". The ceiling is read from the format's own `FormatCapabilities`, so the number a
definition was validated against at startup and the number the writer enforces are the same one.

Sheet names are sanitised to Excel's rules (31 characters, none of `[]:*?/\`, no leading or trailing
apostrophe) and deduplicated case-insensitively, because Excel *rejects* a workbook that breaks any
of them rather than repairing it.

### Template mode, and why it re-reads instead of watching

A definition may name a branding template. `DirectoryXlsxTemplateSource` resolves the name under the
configured template directory and then the classpath, refuses anything that escapes either, and
**re-reads the file on every run**.

The alternative considered was `hot-reload-spring-boot-starter`'s watcher, which is what the
FreeMarker templates use. That is the right mechanism there and the wrong one here, and the
difference is read frequency: a notification template is rendered thousands of times a minute, so a
cache behind a watcher pays for itself; a report template is read once per run, and that run then
spends minutes walking a million rows. Caching would save milliseconds out of minutes and would buy
a second source of truth that can go stale, a watcher thread, and a class of bug where the file on
disk and the file in the workbook disagree. Re-reading is both simpler and strictly more correct.

The template name is on the **definition**, not the request, and it is a name rather than a path: a
template is the letterhead of a document leaving the organisation, so a request that could name a
file would be a request that could read one.

### A note on CSV and totals

CSV declares `totalsRow=false`, so a definition that asks for a totals row does not get one in CSV
and does get one in XLSX. That is the capability check doing its job rather than a gap: a
grand-total line appended to a data file is a record with the wrong shape in it, and the file is
usually read by a parser. The omission is a capability, not a silent downgrade - it is visible in
`FormatCapabilities` and asserted by the module's own tests.

### Performance baseline

Measured, not estimated. The numbers below are the output of `ReportLoadTest`, which is the test that
produces them — run it yourself with the command in this module's `pom.xml`.

**1,000,000 rows x 25 columns, 8 of them enriched across 3 stages, written to XLSX *and* CSV in one
pass over the source:**

| | |
|---|---|
| Elapsed | **21.7 s** |
| Throughput | **47,619 rows/s** |
| Peak heap (sampled) | **168 MiB** |
| Retained after the run | **21 MiB** |
| Full collections | **5** |
| XLSX produced | 93.7 MB |
| CSV produced | 284.2 MB |
| JVM heap | `-Xmx512m` |

Machine: Apple Silicon (`aarch64`), macOS, OpenJDK 26.0.2, enrichment against in-process stubs. The
partner latency of a real estate dominates this number, which is the point of the cache and the
bounded fan-out — a run against real services is bounded by them, not by the engine.

**What the test asserts, and what it only reports.** The completion itself is the memory assertion: a
512 MiB heap is small enough that an engine which stopped streaming would exhaust it, and the test
refuses to run with a heap larger than 640 MiB rather than pass vacuously. *Retained* heap after a
collection is asserted against 128 MiB, because that is retention rather than garbage and is what
catches a slow leak. The sampled peak is reported but not asserted — "used" includes garbage the
collector has not reached, so on a small heap it legitimately approaches `-Xmx` without the engine
holding any of it.

Two things the first version of this test got wrong, both of them measurement rather than engine:
summing per-pool `getPeakUsage()` reported 542 MiB of peak on a 512 MiB heap (the peaks happen at
different moments), and an in-memory test sink reported 383 MiB retained — which was the 93 MB
workbook and the 284 MB CSV the fixture was holding. Both are documented in the test.

---

## Operational runbook

Four things go wrong with a reporting engine in production. Each of them has a signal that says
which one it is, and the signals are deliberately different so that an operator never has to guess.

### Runs are piling up in `PENDING`

**Look at:** `select status, count(*) from export_report_run group by status`, then
`select id, attempts, next_attempt_at, failure_code from export_report_run where status = 'PENDING'
order by next_attempt_at limit 20`.

The question is whether the queue is *waiting* or *stuck*, and `attempts` answers it.

| What you see | What it means | What to do |
|---|---|---|
| `attempts = 0`, `next_attempt_at` in the past, nothing RUNNING | No poller is claiming. | Check `ludwig.export.poller.enabled` on the instances, and that they have a datasource. The poller logs `Export run poller started` at INFO once per instance; absence of that line is the whole diagnosis. |
| `attempts = 0`, some rows RUNNING, the backlog draining slowly | The queue is simply longer than the throughput. | Raise `ludwig.export.poller.concurrency` **only after** checking heap: each concurrent run holds its own window budget. Adding instances is the safer lever - runs are claimed with `SKIP LOCKED`, so instances never collide. |
| `attempts` climbing, `failure_code` set | Runs are failing and retrying. | Read the code. It is the same code the caller sees, and `Failure semantics` above says what each one means. A configuration or parameter fault will not fix itself: cancel the runs rather than letting them consume the retry budget. |
| `next_attempt_at` far in the future | The backoff has grown. | Expected after repeated failures; `ludwig.export.poller.retry-max-delay` is the ceiling. |

**Do not** delete PENDING rows to clear a backlog. A run row is the only record that somebody asked
for a file; cancel them instead (`DELETE /api/v1/reports/runs/{id}`), which records who cancelled
what and leaves the trail intact.

### Runs are stuck in `RUNNING`

This is the instance-death case, and it resolves itself. A claimed run carries `claimed_by`,
`heartbeat_at` and `lease_until`; the poller reclaims any run whose lease has expired before it
claims anything new. So a killed instance costs one lease period, not a lost report.

**Look at:** `select id, claimed_by, heartbeat_at, lease_until, now() from export_report_run where
status = 'RUNNING'`.

| What you see | What it means | What to do |
|---|---|---|
| `lease_until` in the future, `heartbeat_at` recent | The run is alive and working. | Nothing. A million-row report legitimately takes minutes. |
| `lease_until` in the past | The owner died. | Nothing - the next poller tick reclaims it. If it does not, no poller is running; see above. |
| `heartbeat_at` stale but `lease_until` still ahead | The owner is alive but its heartbeat thread is not. | Worth a thread dump: the heartbeat runs on the module's own scheduler, and its starvation means that scheduler is saturated. |
| A run that has been RUNNING far longer than its wall-clock budget | The budget is not being enforced, which should be impossible. | Capture a thread dump before cancelling: this is a defect worth a report, not an operational event. |

**A retry restarts from scratch.** That is safe only because a run is idempotent on its request hash
- the same request produces the same file - and it is why a partially written file is never reused.
Expect a reclaimed run's elapsed time to start again from zero.

### A partner has degraded

**Look at:** the `ludwig.export.enrichment.degraded` counter and `degraded_stages` on recent runs.

A degraded stage is never silent - that is the design. The run row lists it, the file's metadata
sheet lists it, every affected cell carries a localized marker, the download response carries
`X-Report-Degraded: true`, and the counter moves. If you are reading a blank cell and wondering, the
cell is not blank: look again at the marker text.

| What you see | What it means | What to do |
|---|---|---|
| `degraded` climbing for one stage, runs still SUCCEEDED | That stage's `FailurePolicy` is `DEGRADE`. | The partner is the problem, not this module. The rest of the file is correct and complete. |
| Runs FAILED with `export.enrichment.failed` | That stage's policy is `FAIL_REPORT`. | Same partner problem, different declared answer to it. Do not change the policy to make the alert stop: a report whose author chose `FAIL_REPORT` said that a file without that column is worse than no file. |
| `not-found` climbing without `degraded` | The partner is healthy and does not know the keys. | A data question, not an availability one: something upstream is producing references the partner has never had. |
| A stage's latency dominating run time | The fan-out is bounded by the client's pool. | Raise the partner's `max-per-route` **and** the stage's `concurrency` together; the startup validator refuses the pair if you raise only the second. |

### The temp directory is filling

**Look at:** `du -sh` on `ludwig.export.temp.directory`, and the number of files in it.

The engine deletes its temp file on every exit path, including cancellation and failure, and sweeps
orphans older than `ludwig.export.temp.orphan-age` at startup. So a directory that grows has one of
three causes.

| What you see | What it means | What to do |
|---|---|---|
| Files named `ludwig-export-*` with recent timestamps | Runs in flight. | Nothing. Expect roughly one file per concurrent run per format. |
| Files older than `orphan-age` accumulating | The sweep is not running, or the process cannot delete them. | The sweep logs what it removed at INFO on startup. Check ownership: the files are created `rw-------` by the running user, and a directory shared with a differently-owned process is the usual cause. |
| The directory growing while no runs exist | Something other than this module is writing there. | The module only ever creates files with the `ludwig-export-` prefix; anything else is not its. |

Separately, **the sink is not the temp directory**. Stored outputs live under
`ludwig.export.sink.directory` until `retention` expires, and the retention purge deletes them from
the sink before marking the row. A sink that grows without bound means the purge is not running -
check that one instance holds the purge lock (it is leased through `job-core`'s `RunLock`, so exactly
one does) and that `purge-interval` is shorter than `retention`, which the startup validator enforces.

### Nobody can download a file that was produced

**Look at:** `select expires_at, purged_at, sha256 from export_report_output where run_id = ?`.

| What you see | What it means |
|---|---|
| `purged_at` set | Retention expired and the file is gone. The endpoint answers `export.output.expired`, deliberately distinct from "not ready". |
| `expires_at` in the past, `purged_at` null | Expired but not yet swept; the endpoint still refuses it. |
| A 404 rather than a 403 | The caller is not the requester and holds no admin authority. This is intentional: a 403 on a run id would confirm the run exists. |
| `X-Report-Sha256` not matching bytes a recipient still holds | The file was replaced or corrupted in transit. The checksum is of the stored bytes, recorded at store time, so it is comparable months later. |

## Auditing

This module no longer has an audit mechanism of its own. `ExportAuditSink` and `Slf4jExportAuditSink` are gone. Its trail now goes through the
platform's single `AuditSink`, which a deployment points at a log, the append-only `audit_event` table, a
SIEM through the transactional outbox, or several at once - see
[`audit-core`](../audit-core) and [`audit-spring-boot-starter`](../audit-spring-boot-starter).

`ExportAuditEvent` stays exactly as it was - it is the readable authoring surface, and thirteen
components assembled positionally beat thirteen entries put into a `Map<String, Object>` - and
gained a `toAuditEvent()`. Its validation is unchanged: an event with no run id, no status or no
timestamp is still impossible to construct. A run that degraded an enrichment stage is now recorded
as outcome `PARTIAL` rather than as a success with a `degradedStages` field somebody had to notice.

**What a deployment notices:** the `ru.ludwigandreas.export.audit` logger no longer exists. The same information is on
`ru.ludwigandreas.audit` with `category=export`, in a fixed field order, and the run's own fields are
in `attributes`.
