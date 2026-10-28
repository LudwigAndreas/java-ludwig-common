# file-action-spring-boot-starter

***English** · [Русский](README.ru.md)*

Interactive spreadsheet actions: a person uploads an XLSX or CSV and a business action happens - four hundred rows
become four hundred orders, or one order with four hundred lines. A service writes a `RowBinding`, a handler and a
block of YAML; the module owns admission, the size budget, the content sniff, storage before parsing, the idempotency
claim, the scanner seam, the bounded SAX reader, the lifecycle (including confirm), the commit policy, the reject
report, the operation envelope, the HTTP surface, the template, retention and audit.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | `audit-core`, `db-core`, `idempotency`, `job-core`, `object-storage`, `observability`, `outbox`, `security`, `web-core` |
| Used by | `crud-service-example` |
| Surfaces | REST `/api/v1/file-actions/**` (multipart); object storage |

**Not file-ingest.** [`file-ingest`](../file-ingest-spring-boot-starter) is scheduled, partner-driven and resumable
(byte-offset checkpoints, staging, set-based merge). This module is interactive: nothing resumes mid-file (a person
re-submits), rows become domain calls, and failures are sentences naming a sheet, a row and a column heading in the
reader's language. The boundary is a ceiling (25 MiB, 100,000 rows by default): above it, use file-ingest. There is no
shared `file-format-core` until a second module needs to read CSV or XLSX.

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>file-action-spring-boot-starter</artifactId>
</dependency>
```

```java
@FileAction("order-import")
public class OrderImportHandler implements RowHandler<OrderLine> {

    private static final RowBinding<OrderLine> ORDER_LINES = RowBinding.of(OrderLine.class)
            .sheet("Orders")
            .column("sku", "SKU").aliases("Article")
            .column("quantity", "Qty")
            .column("price", "Price")
            .column("dueDate", "Due")
            .column("comment", "Comment").optional()
            .build();

    @Override
    public RowBinding<OrderLine> binding() {
        return ORDER_LINES;
    }

    @Override
    public RowOutcome apply(OrderLine row, FileActionContext context) {
        if (!catalogue.exists(row.sku())) {
            return RowOutcome.rejected("orders.import.unknown-sku", row.sku());
        }
        orders.create(row, context.submissionId());
        return RowOutcome.applied();
    }
}

public record OrderLine(@NotBlank String sku, @Positive Integer quantity,
                        @PositiveOrZero BigDecimal price, LocalDate dueDate, String comment) {
}
```

```yaml
ludwig:
  file-action:
    storage:
      uploads: s3://orders/file-action/uploads
      artifacts: s3://orders/file-action/artifacts
    scanning:
      mode: required
    actions:
      order-import:
        commit-policy: PER_ROW
        mode: CONFIRM
        execution: INLINE
        required-authority: ORDER_IMPORT
```

The endpoints exist as soon as the action is configured. The binding is code: field names are checked against the
record's components at class initialisation, target types come from the record, constraints are Bean Validation.

### The core path

```
POST /api/v1/file-actions/order-import   (multipart)
      1. admit      size ceiling enforced WHILE the body is read
      2. spool      one pass to a temp file through a SHA-256 digest
      3. sniff      magic bytes; the declared content type is a hint only
      4. store      ObjectStore.put under the hash          <- always, before anything parses
      5. claim      IdempotencyStore on hash + action + caller
      6. record     the submission row                      <- before the read starts
      7. scan       the FileScanner seam
      8. dispatch   INLINE: read, bind, validate, apply now -> 200, terminal envelope
                    DEFERRED: 202 + Operation-Location; a worker claims it under a lease
```

The bytes are always stored first: an unstored input cannot be retried, cited in an error report, answer a dispute or
survive pod eviction mid-parse. `INLINE` vs `DEFERRED` is a property with the same response shape, so switching needs
no client change; an `INLINE` action above `inline.max-rows` does not start.

### Lifecycle

```
                        UPLOADED
          +----------------+----------------+
      REJECTED         VALIDATED         APPLYING          (mode: DIRECT skips VALIDATED)
                           | (confirm)      |
                        EXPIRED           APPLIED
                     (TTL elapsed)

  CANCELLED  from VALIDATED at once; from UPLOADED or APPLYING at the next checkpoint
```

**Cancel ends `CANCELLED`** (envelope `CANCELLED`), never `APPLIED`. A `VALIDATED` submission is cancelled at once
(`200`); one being read or applied stops at its next checkpoint - before the apply starts, or between batches - and
the cancel answers `202`. Rows committed before the stop are kept and reported in `rowsApplied`; a batch in flight
is never interrupted. A stop is audited as `file-action.cancelled`. A cancel and a confirm racing on one validated
submission cannot both win.

`FileActionState` is a domain lifecycle in `OperationResponse.detail()` (`UPLOADED` and `VALIDATED` are both
`PENDING`; the difference is the Confirm button). Modes: `CONFIRM` (default - preview, then apply), `DIRECT`
(machine callers, reversible actions), `VALIDATE_ONLY` (never applies). **Confirmation applies the rows the user was
shown** (a bound-row artifact written at validation), not a re-read of the workbook (`ConfirmAppliesPreviewedRowsIT`).

### Handler shapes and commit policy

```
sealed interface FileActionHandler<R>
   |- RowHandler<R>        apply one row       400 rows -> 400 orders   partial success is meaningful
   `- DocumentHandler<R>   apply all rows      400 rows -> 1 order      partial success is meaningless
```

`commit-policy` has **no default** - the action does not start without it:

| Policy | One refused row | Right for |
|---|---|---|
| `ALL_OR_NOTHING` | nothing applied | journal entries, stock takes |
| `PER_BATCH` | that batch rolls back, earlier batches stand | bulk price updates |
| `PER_ROW` | that row is rejected, the rest apply | order imports, contact lists |

A `DocumentHandler` must use `ALL_OR_NOTHING`. `reject-threshold` (default `0.1`) refuses the whole submission when
more than that fraction of rows is refused ("wrong file entirely"); deliberately skipped rows do not count.

### Where a validation rule goes

| | Runs | Names the row | Use for |
|---|---|---|---|
| **Bind time** - coercion, Bean Validation, the record's compact constructor | once per row during the read | yes (sheet, displayed row, header) | every rule whose subject is one row |
| **The handler** | inside the apply transaction | `RowHandler`: yes; `DocumentHandler`: **no** | cross-row rules |

1. **Row rule without lookup** - on the record: a field constraint (points at the cell), a class-level constraint or
   the compact constructor (points at the row). All problems of a row are collected; Bean Validation runs only on a
   record that could be constructed; a row with problems never reaches the handler.
2. **Row rule with a lookup** ("this SKU exists") - a Spring-managed `ConstraintValidator` bean. One query per row, so
   put a `PERFORMANCE`-purpose cache from [cache](../cache-spring-boot-starter) behind it. It must be **stateless**
   (validators are shared singletons), read-only, and its message is interpolated in the validating locale (keep it in
   `ValidationMessages[_ru].properties`).
3. **Cross-row rule** ("lines sum to the total", "no duplicate SKU") - a `DocumentHandler`:

```java
@FileAction("order-import")
public class OrderImportHandler implements DocumentHandler<OrderLine> {

    @Override
    public RowBinding<OrderLine> binding() {
        return OrderBindings.ORDER_LINES;
    }

    @Override
    public RowOutcome apply(Stream<OrderLine> rows, FileActionContext context) {
        Order order = Order.draft(context.submissionId());
        Map<String, Integer> quantityBySku = new LinkedHashMap<>();

        rows.forEach(line -> {                       // fold once, never collect
            quantityBySku.merge(line.sku(), line.quantity(), Integer::sum);
            order.addLine(line);
        });

        // cross-row checks BEFORE the dry-run return, so the CONFIRM preview shows them
        if (order.lineCount() == 0) {
            return RowOutcome.rejected("order.import.no-lines");
        }
        if (quantityBySku.size() != order.lineCount()) {
            return RowOutcome.rejected("order.import.duplicate-sku", firstDuplicate(quantityBySku));
        }
        if (context.dryRun()) {
            return RowOutcome.applied();             // validated; nothing written
        }
        orders.save(order);
        return RowOutcome.applied();
    }
}
```

A `DocumentHandler` must: consume the stream once; run cross-row checks before the `dryRun()` return; change nothing
when `dryRun()` is true; put identifying values into reject arguments; make no partner calls inside the transaction
(use the outbox); return a reject rather than throw for document problems.

**A document action must set `reject-threshold: 0`.** Rows that fail binding never reach the handler; with the default
`0.1`, 30 bad lines out of 400 are silently dropped and the order is created with 370 lines. The validator does not
enforce this (`docs/harness-enforcement.md`).

```yaml
ludwig:
  file-action:
    actions:
      order-import:
        commit-policy: ALL_OR_NOTHING
        mode: CONFIRM
        reject-threshold: 0
        execution: DEFERRED            # a large document: one long transaction, off the request
        error-report: ANNOTATED_WORKBOOK
```

### Reading an untrusted spreadsheet

| Threat | Defence |
|---|---|
| Memory blow-up | SAX only (`XSSFReader` + `XSSFSheetXMLHandler`); `PoiConfinementTest` forbids DOM readers |
| Zip bombs | Compression-ratio floor, entry count and total-inflation ceilings decided from the ZIP central directory (per action); POI's JVM-global `ZipSecureFile` ratio as backstop |
| Shared-strings table | Checked separately from the directory (it is the real memory ceiling) |
| External links | `/xl/externalLinks` refused |
| Legacy `.xls` | Detected and refused with "save it as .xlsx" |
| Renamed files | Format from magic bytes |
| Formula injection in generated files | Values starting `=`, `+`, `-`, `@` neutralised |
| XML entities | DTDs and external entities off for `xl/workbook.xml` |

**Locale.** Cells are coerced in the **submitting caller's** locale and zone (stored on the submission): `1,234` differs
by locale; Excel's comma-decimal grouping separator is a no-break space; date cells are numbers (no day/month
ambiguity); the 1904 epoch (`date1904="1"`, Excel for Mac) is honoured; two-digit-year short patterns are widened.

### Rejects

Per-file problems are RFC 9457 documents through web-core. Row rejects are an **artifact**:

- `file_action_row_reject` stores a **bounded sample** (default 100); `rowsRejected` vs `rejectsStored` tells a UI
  there are more;
- `GET .../rejects` (paged) and `error-report: ANNOTATED_WORKBOOK` - the user's own sheet with a `Problems` column (CSV
  submissions get a CSV report);
- addressed by sheet, **1-based displayed row** and **header name**; stored as code + arguments, rendered in the
  reader's language.

### HTTP surface (fixed base path)

```
POST   /api/v1/file-actions/{action}                    multipart -> 200 | 202  SubmissionResponse
GET    /api/v1/file-actions/{action}/{id}               -> SubmissionResponse (+ Retry-After while running)
POST   /api/v1/file-actions/{action}/{id}/confirm       -> 200 | 202
POST   /api/v1/file-actions/{action}/{id}/cancel        -> 202 (cooperative) | 200 (VALIDATED, or already finished)
GET    /api/v1/file-actions/{action}/{id}/rejects       -> PageResponse<RowRejectResponse>
GET    /api/v1/file-actions/{action}/{id}/error-report  -> annotated workbook or reject CSV
GET    /api/v1/file-actions/{action}/template           -> blank workbook generated from the binding
```

`SubmissionResponse` nests the platform `OperationResponse` plus row counts, reject counts and whether Confirm is
needed. Idempotency key: `sha256(content) + action + caller` via `IdempotencyStore` (double clicks and retried POSTs
get the first submission's envelope). `IdempotencyFilter` is not used (it buffers bodies in memory).

`RuleGroup.UPLOADS` in architecture-rules fails the build if any other module names `MultipartFile`.

## Configuration

```yaml
ludwig:
  file-action:
    enabled: true
    scanning:
      mode: required                 # required | optional | disabled - fails closed
    storage:
      uploads: s3://orders/file-action/uploads        # no default
      artifacts: s3://orders/file-action/artifacts    # no default
      retention: P7D
      retention-interval: PT1H
      retention-batch-size: 100
      retention-lease: PT5M
    inline:
      max-rows: 5000
    deferred:
      lease: PT5M
      batch-size: 5
      max-attempts: 3
      interval: PT10S
      initial-delay: PT15S
      drain-timeout: PT30S
    defaults:                        # every key may be overridden per action
      max-size: 25MB
      max-rows: 5000                 # = inline.max-rows, because execution defaults to INLINE
      max-rows-limit: 100000         # the module's ceiling; above it, use file-ingest
      formats: [xlsx, csv]
      batch-size: 500
      reject-sample: 100
      reject-threshold: 0.1
      confirm-ttl: PT30M
      error-report: ANNOTATED_WORKBOOK
      archive:
        max-entries: 1000
        min-inflate-ratio: 0.01
        max-uncompressed: 512MB
        max-shared-strings: 16MB
        max-cell-characters: 32000
        read-timeout: PT2M
    actions:
      order-import:
        commit-policy: PER_ROW       # no default
        mode: CONFIRM                # DIRECT | CONFIRM | VALIDATE_ONLY
        execution: INLINE            # INLINE | DEFERRED
        required-authority: ORDER_IMPORT
```

`storage.uploads` / `storage.artifacts` have no defaults: the only possible default is a local directory, which in
production means a pod's ephemeral disk.

**Startup refuses** (all problems, each naming action, property and acceptable values): an action without a
`@FileAction` bean or a bean without configuration; no `commit-policy`; a row-level policy on a `DocumentHandler`;
`scanning.mode: required` without a `FileScanner` bean; an `INLINE` action above the inline ceiling or any action above
`max-rows-limit`; `required-authority` without the security starter; `reject-threshold` outside 0..1 or a non-positive
`batch-size`; a problem code missing in either shipped locale; a `@FileAction` bean that is not a `FileActionHandler`.

## Deployment

- **Schema** (`db/changelog/file-action/file-action-changelog.xml`): `file_action_submission` (the queue, leased,
  `@Version`) and `file_action_row_reject`.
- **Buckets/prefixes** for uploads and artifacts via [object-storage](../object-storage-spring-boot-starter).
- **A `FileScanner` bean** (ICAP, `clamd`, cloud API) - the module ships the SPI and no implementation; or
  `scanning.mode: disabled`, stated explicitly.
- **Multipart limits**: `spring.servlet.multipart.max-file-size` / `max-request-size` at least `max-size`; ingress body
  limits likewise.
- **Deferred worker**: no `RunLock` (instances share the queue); **retention**: under a `RunLock`, deletes objects
  first, then marks rows `EXPIRED`.

## Observability

**Logs.** `INFO` retention totals. `WARN`: a submission that could not be processed, one that failed `max-attempts`
times and will not be retried, a reject report that could not be produced, temp/spool cleanup failures, objects that
could not be deleted during retention (orphaned).

**Known gaps:** the module publishes **no metrics** - no counters for submissions, applied/rejected rows, scanner
refusals, deferred queue depth or retention, and no timers for read/apply duration. Operating it today relies on logs,
the audit trail and SQL. Health of the deferred queue is visible only through
`SELECT state, count(*) FROM file_action_submission GROUP BY state;`.

## Audit

Category `file-action`:

| Action | When |
|---|---|
| `file-action.submitted` | Upload accepted and stored |
| `file-action.duplicate` | Same content + action + caller resubmitted |
| `file-action.scanned` | Scanner verdict |
| `file-action.rejected` | File-level refusal or reject threshold |
| `file-action.validated` | Validation finished (preview ready) |
| `file-action.confirmed` | User confirmed |
| `file-action.applied` | Rows applied |
| `file-action.cancellation-requested` | Cancel requested |
| `file-action.cancelled` | The submission stopped and ended `CANCELLED` |
| `file-action.expired` | Retention removed artifacts (actor `system`) |

Recorded: filename, content hash, size, counts, scanner answer, outcome - **never a cell of the file**.

## Administration

Operator endpoints are on the platform's administrative surface, `/admin/v1/{area}`, and exist only where `security-spring-boot-starter`'s surface does. Each is guarded by one operation; which of the service's roles hold it is the service's own `ludwig.admin.grants` (roles from the authority resolver, never token claims), and an operation granted to no role is refused to everyone. A `privileged` call needs an `Admin-Reason` header, and every call is audited under category `admin` with the operation id as its action. `GET /admin/v1/me/operations` lists what the caller holds.

| Operation | Endpoints | Allows |
|---|---|---|
| `fileactions.read` | `GET /admin/v1/fileactions/submissions`<br>`GET /admin/v1/fileactions/submissions/{id}` | See every submitter's file-action submissions, without the file or any row. |
| `fileactions.operate` | `POST /admin/v1/fileactions/submissions/{id}/cancel` | Cancel anybody's file-action submission. |
| `fileactions.privileged` | none in this release | No operation yet; reserved so that grants for this area stay the same across releases. |

Responses carry no file, no row content and no error-report link. Cancel is the submitter's own cancel: `202` while the stop is pending, or `200` with the envelope when the submission is terminal - including one it ended `CANCELLED` itself, as a `VALIDATED` one is.

Grant block (the role names are the service's own):

```yaml
ludwig:
  admin:
    grants:
      "[fileactions.read]": [ORDERS_ADMIN, ORDERS_SUPPORT]
      "[fileactions.operate]": [ORDERS_ADMIN]
      "[fileactions.privileged]": [ORDERS_ADMIN]
```

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| Startup fails listing file-action problems | Missing `commit-policy`, scanner, bean, or inconsistent limits | Fix each named property |
| Upload refused `too-large` / `too-many-rows` | Above `max-size` / `max-rows` | Use file-ingest for bulk, or raise per action deliberately |
| `suspicious-archive` / `external-references` / `legacy-xls` / `unsupported-format` | Defences above | Ask the user to re-save as plain `.xlsx` |
| `missing-column` / `no-header-row` / `sheet-not-found` | Template mismatch | Point the user at `GET .../template` |
| `too-many-rejects` | Wrong file or bad data | User downloads `error-report`, fixes, re-submits |
| `confirm-window-closed` | `confirm-ttl` elapsed | Re-submit |
| `scan-rejected` | Scanner flagged the file (file deleted) | Security review if unexpected |
| Deferred submissions not progressing | Worker stopped, or max attempts reached | `SELECT id, action, state, attempts, failure_code, lease_expires_at FROM file_action_submission WHERE state NOT IN ('APPLIED','REJECTED','EXPIRED') ORDER BY created_at;` and the `has failed N times` warning |
| Document action created partial aggregates | `reject-threshold` not 0 | Set `reject-threshold: 0` for `DocumentHandler` actions |

Problem codes (prefix `file-action.`): `too-large`, `too-many-rows`, `unsupported-format`, `legacy-xls`, `unreadable`,
`suspicious-archive`, `external-references`, `missing-column`, `no-header-row`, `sheet-not-found`, `empty`,
`scan-rejected`, `too-many-rejects`, `wrong-state`, `not-found`, `confirm-window-closed`, `not-confirmable`,
`forbidden`, `unknown-action`; row codes `cell-not-coercible`, `cell-required`, `ragged-row`, `constraint-violated`.

## Testing

Unit tests (commit policies asserted on commit/rollback, coercion, locale, epochs, archive inspection), integration
tests (Docker) including `ConfirmAppliesPreviewedRowsIT` and `LargeWorkbookHeapIT` (100,000 rows all delivered), and
`LargeWorkbookHeapMeasurementIT` (tagged `measurement`, run explicitly):

```bash
mvn -pl :file-action-spring-boot-starter verify \
    -Dfile-action.test.excluded.groups= -Dit.test=LargeWorkbookHeapMeasurementIT \
    -DargLine="-Xmx256m"
```

It asserts retained heap is not proportional to the file and that a DOM read retains at least 5× more (proving the
budget can fail). `PoiConfinementTest`, `NoSqlStringsTest` (no SQL carve-out here).

**Cannot be enforced, written down instead** (also in `docs/harness-enforcement.md`): a `RowHandler` must be
idempotent per row (a lease takeover may re-apply a batch); a `DocumentHandler` must not call partners inside the
transaction; whether the declared `commit-policy` suits the domain; whether a `FileScanner` actually scans.

## Design decisions

- **Store before parse**, always.
- **Confirm is the default**: a wrong apply has no undo.
- **No default commit policy**: every default is silently wrong for someone.
- **Rejects are an artifact**, bounded and addressed by header name, not a 400 with thousands of entries.
- **Scanning fails closed.**
- **The submission row is the queue** (no message broker to disagree with it).
- **Retention is the module's job**, not a bucket lifecycle rule (which would leave `SUCCEEDED` pointing at nothing).
- **Not here**: resumable checkpoints, staging/merge, `.xls`/`.ods`/HTML/PDF, a scanner implementation, a UI, reuse of
  export's writer.
