# file-action-spring-boot-starter

[English] | [Русский](README.ru.md)

A person drags a spreadsheet into a browser and a business action happens: four hundred rows of an
Excel sheet become four hundred orders, or one order with four hundred lines.

A service writes a `RowBinding`, a handler, and a block of YAML. Everything else is this module.

| | |
|---|---|
| **the author writes** | which columns map to which fields, and what a row *means* |
| **the module owns** | admission, the size budget, the content sniff, storage before parsing, the idempotency claim, the scanner seam, the bounded reader, the lifecycle, the commit policy, the reject report, the operation envelope, the HTTP surface, the template, retention, metrics and audit |

## Why this is not `file-ingest-spring-boot-starter`

The two read files and are not the same module, and merging them would break both.

[`file-ingest`](../file-ingest-spring-boot-starter) is **scheduled and partner-driven**: it discovers
an object somebody dropped in a bucket, parses it against a byte-offset checkpoint so a crash resumes
with a ranged GET, writes staging rows, and merges them into a target with hand-written SQL. Its
contract is built around four million rows at six-thirty in the morning with nobody watching.

This module is **interactive and person-driven**. Nothing resumes mid-file, because a request a person
is waiting for is not resumed - it is re-submitted. There is no staging table, because the rows become
a domain call rather than a bulk insert. And the output of a failure is not a quarantine row with a byte
offset in it; it is a sentence naming a sheet, a row number and a column heading, in the reader's
language, in a file they can fix and re-upload.

That last difference is why the two parser contracts are separate. `RecordParser` reports absolute byte
offsets and declares a `CheckpointKind`; neither has any meaning here, and what this module's reader
carries instead - a sheet, a displayed row number, cells keyed by header name - `RecordParser` has no
vocabulary for.

**There is deliberately no shared `file-format-core`.** `file-ingest` ships no concrete parser at all
(every implementation lives in a consuming service), so there was nothing to consolidate, and a module
extracted for one consumer is speculative generality. The condition that would change that is written
down rather than left to drift: *a second module needing to read CSV or XLSX*.

### Where the boundary actually is

This module declares a ceiling - 25 MiB and 100,000 rows by default - and refuses a larger file with a
message that names `file-ingest`. Above the ceiling is a scheduled bulk import; below it is something a
person is waiting for. The ceiling is configurable, and
[`LargeWorkbookHeapMeasurementIT`](#the-memory-guarantee) is what makes raising it an evidenced decision
rather than a hopeful one.

## What a service writes

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
```

```java
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

That is the whole of it. The endpoints below exist as soon as the action is configured.

### Why the binding is code and not a row in a table

The decision [`export`](../export-spring-boot-starter) takes for its report definitions, for the same
reason. The obvious way to make an import engine reusable is to let an administrator declare the mapping
as data through a UI, and that is precisely how an import module becomes a second, untested binding layer
with no type checking, no refactoring support and no answer to "which imports write this field".

Declared as a constant, the field names are checked against the row record's components when the class
initialises - so a renamed component is a startup failure in every environment rather than an empty
column in production. The target types come from the record. The constraints are Bean Validation on the
record. What stays data is the part that genuinely varies per deployment: the size budget, the commit
policy, the confirm window.

## The core path

```
POST /api/v1/file-actions/order-import   (multipart)
      |
      1. admit      size ceiling enforced WHILE the body is read, not from a declared length
      2. spool      one pass: the body to a temp file, through a SHA-256 digest
      3. sniff      magic bytes; the declared content type is a hint only
      4. store      ObjectStore.put under the hash           <-- ALWAYS, before anything parses
      5. claim      IdempotencyStore on the hash + action + caller
      6. record     the submission row                       <-- before the read starts
      7. scan       the FileScanner seam, if one is configured
      |
      8. dispatch --+-- INLINE   read, bind, validate, apply now; answer 200, terminal envelope
                    +-- DEFERRED answer 202 + Operation-Location; a worker claims it under a lease
```

Nothing parses the file until the first six have happened, and that is not an implementation detail.

### Why the bytes are always stored first

This was going to be a tier - stream small files straight through, persist only the large ones - and it
is wrong on five counts, the first of which settles it.

**The "streaming" path was never streaming.** Spring has already spooled the multipart to
`java.io.tmpdir` before a controller method runs, so skipping the store saves no write; it only picks
the worst possible storage for one. Beyond that, a request that has not persisted its input cannot be
retried, cannot produce an error report that cites the original, cannot answer a dispute three days
later, and cannot survive the pod being evicted mid-parse while the user's browser holds an open
connection.

`FilesystemObjectStore` exists, so "always the object store" does not impose S3 on a small deployment.

### Why `INLINE` and `DEFERRED` are a property and not two endpoints

Both answer with the same `OperationResponse`. Inline is simply the case where the envelope is already
terminal when it is returned, which the platform's long-running-operation contract permits explicitly:
*"A 202 may carry a terminal envelope, and a 200 must."*

What that buys: a deployment that discovers its inline action is too slow changes one property, and no
client changes at all. Had the two been separate endpoints, the same discovery would have been a
breaking API change - which is the kind of change nobody makes and everybody lives with.

A guard rail comes with it. An `INLINE` action configured above `ludwig.file-action.inline.max-rows`
does **not start**: an inline action sized for a hundred thousand rows is a request timeout and a held
connection, discovered in production.

## The lifecycle

```
                        UPLOADED
                           |
          +----------------+----------------+
          |                |                |
      REJECTED         VALIDATED         APPLYING          (mode: DIRECT skips VALIDATED)
                           |                |
                      (confirm)             |
                           +----------------+
                           |                |
                        EXPIRED           APPLIED
                     (TTL elapsed)
```

`FileActionState` is a domain lifecycle carried in `OperationResponse.detail()`, not a second status
vocabulary. `UPLOADED` and `VALIDATED` are both `PENDING`, and the difference between them is the whole
confirm feature: a client showing a spinner for one and a **Confirm** button for the other cannot tell
them apart from the core status alone. `architecture-rules`' `RuleGroup.OPERATIONS` is what fails the
build if somebody adds `COMPLETED` here.

### Why `CONFIRM` is the default

"Drag in a sheet and four hundred orders appear" with no confirm step is four hundred wrong orders and
no undo. The cost of a wrong apply is borne by whoever has to unpick it, and the preview is the only
point at which stopping is cheap. `DIRECT` exists for machine callers and genuinely reversible actions;
`VALIDATE_ONLY` never applies anything and is what a UI calls for inline feedback.

### A confirmation applies the rows the user was shown

Validation writes the bound rows to an artifact, and confirmation applies **that artifact** - it does not
re-read the workbook.

The obvious implementation re-reads and re-binds: it is simpler, needs no artifact, and passes every test
in which nothing changes between the two requests. What it does is apply something other than the preview
whenever anything *has* changed - a reference table, a price list, a product that was withdrawn - silently,
in the case that is hardest to reproduce. `ConfirmAppliesPreviewedRowsIT` replaces the stored object's
bytes between validate and confirm and asserts that the previewed rows are still what gets applied.

The artifact is why `DIRECT` and `CONFIRM` are one code path rather than two, and it is also what lets a
`DocumentHandler` be handed a genuinely lazy `Stream` over a push-based SAX read.

## The two handler shapes

```
sealed interface FileActionHandler<R>
   |- RowHandler<R>        apply one row       400 rows become 400 orders     partial success is meaningful
   `- DocumentHandler<R>   apply all rows      400 rows become 1 order        partial success is meaningless
```

`file-ingest`'s `RecordApplier` is per-record only, deliberately, so one bad record is one quarantine row
rather than a failed batch of five thousand. That is right for a staging import and wrong here, because an
Excel sheet whose rows are the *lines of one order* is a single business fact and "we created the first 38
lines of your order" is not an outcome anybody wants. A module offering only the per-row shape would have
every author of an aggregate import fold the rows up by hand inside a per-row callback, keeping mutable
state across invocations the module is free to batch, retry and parallelise.

The consequence that makes the split load-bearing: **the commit policy applies only to `RowHandler`.** A
`DocumentHandler` is all-or-nothing by construction, and an action configuring anything else for one does
not start. `FileActionHandler` is sealed for the reason `reconciliation`'s `Fetcher` is: a third shape
would be either a renaming of one of these or an admission that the module does not know what partial
success means for it.

## The one declaration the module cannot make for you

`commit-policy` has **no default**, modelled on `cache-spring-boot-starter`'s `CachePurpose` - which
`CLAUDE.md` singles out as "the only mistake the module cannot detect for you".

| policy | one refused row | right for |
|---|---|---|
| `ALL_OR_NOTHING` | nothing is applied | a journal entry, a trial balance, a stock take - anything where a partial result is a wrong result |
| `PER_BATCH` | that batch rolls back, earlier batches stand | a bulk price update, where re-sending the tail is cheap |
| `PER_ROW` | that row is rejected, the rest apply | an order import, a contact list, a catalogue update |

Every candidate default is silently wrong for somebody. Default to `PER_ROW` and an accounting import
applies two thirds of a journal entry; default to `ALL_OR_NOTHING` and a four-hundred-row contact list is
refused in full over one mistyped email, which users experience as the feature not working. Neither failure
is visible in a test - a fixture has either no bad rows or all bad rows - and both are visible in
production, as applied business data. So an action that does not declare one does not start, and the
message names the three values.

The three differ in exactly one thing: **the transaction boundary.** `PER_BATCH` and `PER_ROW` share a
boundary and differ only in what a refused row does to it, which is why `CommitPolicyTest` asserts on
commit-versus-rollback rather than on applied counts: a test counting rows passes for both on a clean file
and would not notice the two being swapped.

Orthogonally, `reject-threshold` (default `0.1`) refuses the whole submission when more than that fraction
of rows is refused. It is the "this is the wrong file entirely" guard: applying the sixty per cent that
happened to parse is never what anybody wanted, and a user who uploaded last year's template would rather
be told. A deliberately skipped row does not count toward it - a file whose last two hundred rows are
intentional duplicates is not the wrong file.

## Reading an untrusted spreadsheet

This is the part with teeth. An XLSX is a ZIP of XML chosen by a user.

| What | How |
|---|---|
| **No DOM reader, ever** | `XSSFReader` + `XSSFSheetXMLHandler` SAX. `new XSSFWorkbook(in)` turns a 4 MB crafted workbook into hundreds of megabytes resident, so the heap a service needs becomes a function of what a user uploads - and the failure is an `OutOfMemoryError` that takes the pod down, in production, never in a test. `PoiConfinementTest` fails the build on the reference |
| **Zip bombs** | compression-ratio floor, entry count and total-inflation ceilings, all decided from the ZIP **central directory** so nothing is decompressed to make the decision |
| **Shared strings** | POI materialises that table whatever the reader does, so it is the real memory ceiling of an XLSX read and is checked separately, also from the directory |
| **External links** | an `/xl/externalLinks` part is a refusal. A workbook taking values from another file would import whatever POI last cached - stale or empty, and in both cases not what the user is looking at |
| **Legacy `.xls`** | refused, and *detected* so the message can say "save it as .xlsx". `HSSFWorkbook` has no streaming mode at all, so supporting BIFF means a heap proportional to a user-supplied file |
| **A renamed file** | the format comes from magic bytes. Users rename `.csv` to `.xlsx` routinely, and handing a ZIP parser a text file produces an error about a corrupt archive that tells them nothing |
| **Formula injection on the way out** | a value beginning `=`, `+`, `-` or `@` is neutralised in every generated file. The report is opened by whoever administers the import, not by whoever uploaded it |
| **XML entities** | the one XML document this module parses itself - `xl/workbook.xml`, for the date epoch - has DTD support and external entities off |

### Why POI's own guard is not enough on its own

`ZipSecureFile.setMinInflateRatio` is a **JVM-global static**, so it cannot be a per-action ceiling -
and `export` shares the JVM while reading administrator-supplied templates under different assumptions.
So the per-action ceilings are enforced from the central directory, where they can differ per submission,
and POI's global stays as a conservative backstop for the case the directory lies about. Two mechanisms,
one cheap and precise and one expensive and honest, and the reason both exist is recorded at
`ArchiveInspector` so neither is removed as redundant.

### The memory guarantee

`PoiConfinementTest` stops the DOM readers being *referenced*. It cannot tell whether the SAX path
actually bounds the heap, which is a runtime property of a hundred thousand rows - and the whole module
rests on the claim that it does.

- `LargeWorkbookHeapIT` runs on every build and asserts the deterministic half: every row of a
  hundred-thousand-row workbook is handed over, and the last one carries the values the fixture wrote.
  A reader that bounded its memory by quietly stopping, or dropped rows under pressure, fails here.
- `LargeWorkbookHeapMeasurementIT` holds the measurement, behind `@Tag("measurement")`. It asserts a
  *ratio* - that retained heap after a hundred thousand rows is not proportional to the file - rather than
  an absolute byte ceiling, which would be tuned to whichever machine first ran it. It also performs a DOM
  read of the same file and asserts it retains at least five times more, so the budget is **proven capable
  of failing** rather than assumed to be.

```bash
mvn -pl :file-action-spring-boot-starter verify \
    -Dfile-action.test.excluded.groups= -Dit.test=LargeWorkbookHeapMeasurementIT \
    -DargLine="-Xmx256m"
```

It is tagged out of the default build not because it is slow - it takes seconds - but because a heap ratio
measured in a JVM shared with the other integration tests and instrumented by JaCoCo is not reliable: it
passed alone and failed in the full suite. A flaky guarantee is worse than an honest tag, because a test
that goes red for reasons unrelated to what it guards is one somebody eventually deletes.
`export`'s load test is arranged the same way.

## Locale, and the dates nobody notices are wrong

Every cell coercion reads the **submitting caller's** locale and zone, recorded on the submission row at
admission - not the thread's, because a deferred submission is read on a worker with no request bound to
it and a confirmation may come from a different person.

- `1 234,56` and `1,234.56` are the same number for a Russian and an American caller, and `1,234` is
  *not*: it is a number near one for the first and near a thousand for the second. Read against
  `Locale.getDefault()` one of them is silently wrong, and which one depends on the container's locale
  rather than on anything in the request. `RuleGroup.PRESENTATION` forbids that accessor.
- Excel's grouping separator in comma-decimal locales is a **no-break space**, not an ordinary one.
- A workbook cell holding a date holds a *number*. This module converts the number, so `01.02.2026` is
  never parsed and the question of February versus January never arises.
- There are **two date epochs**. A workbook saved by Excel for Mac carries `date1904="1"` and a reader
  that assumes the Windows epoch reads every date in it **four years and one day early** - invisible in
  testing, because fixtures are written by the library doing the reading, and invisible in review, because
  the code looks right. `WorkbookProperties` reads the flag.
- `en-US`'s localised short date pattern has a **two-digit year**, so `2/1/2026` - exactly what an American
  caller types - does not parse against it, and the medium pattern wants a month name. Every localised
  pattern is also tried with its year widened. Found by a test, not by reading.

## Row errors are an artifact, not a `ProblemDetail`

A `ProblemDetail` answers "this file is not acceptable": too large, wrong format, failed the scanner,
unreadable archive, missing a required column. Those are per-file, few, and go through `web-core`'s single
RFC 9457 pipeline, which this module contributes to and ships no `@RestControllerAdvice` of its own for.

Row rejects are a different thing. There can be thousands, and a 400 carrying three thousand entries is
not an API a client can render or a person can read.

- `file_action_row_reject` stores a **bounded sample** (default 100). An unbounded table is a hundred
  thousand rows per bad upload, on a table whose only query is "give me the first page".
- `GET .../rejects` serves them paged. Comparing `rowsRejected` with `rejectsStored` is how a UI knows to
  say "showing the first hundred of three thousand".
- `error-report: ANNOTATED_WORKBOOK` gives the user **their own sheet, with a `Problems` column** - the
  form they can fix and re-submit as it stands. A CSV submission falls back to a CSV report, because there
  is no workbook to annotate.
- Addressing is sheet, **1-based displayed row** (including the header, so it matches the row gutter) and
  the column's **header name**. Never a column index: an index is wrong the moment somebody inserts a
  column, and it is unactionable to a user either way.
- A reject is stored as a **code and its arguments**, never a rendered sentence - so a report produced on a
  worker, or read back months later, renders in the reader's language, and a bundle correction reaches
  rows already stored.

## Scanning fails closed

`ludwig.file-action.scanning.mode` is `required` (the default), `optional` or `disabled`. The module ships
the `FileScanner` SPI and **no implementation**: which scanner an estate runs - an ICAP appliance, a
`clamd` socket, a cloud API - is a deployment decision with its own credentials and failure modes.

With `required` and no `FileScanner` bean, **the application does not start**, and the message names the
value that turns the requirement off. So the only two honest outcomes are "a scanner is configured" and
"somebody said out loud that there is none". A file the scanner refuses is deleted, not kept: a bucket of
known-bad files with the same access rules as the good ones is not an improvement.

What this cannot check is whether an implementation actually scans. A bean returning `safe` unconditionally
satisfies every rule here. That gap is in [`docs/harness-enforcement.md`](../../docs/harness-enforcement.md)
rather than left implied.

## Idempotency

The claim key is `sha256(content) + action + caller`, through `IdempotencyStore`. A user double-clicking
the drop zone, or a browser retrying a POST whose response was lost, gets the first submission's envelope
rather than four hundred orders twice. That is the normal case, not an edge one.

`IdempotencyFilter` is deliberately **not** used. Its own `CachedBodyRequest` javadoc says it: *"Buffering
a request body in memory is exactly the mistake `file-ingest` exists to avoid... An endpoint receiving
bodies large enough to matter is not one where this filter should be reading them."* A 25 MiB multipart is
such a body, and the hash this module already computes while streaming serves the same purpose at no extra
pass.

## The HTTP surface

```
POST   /api/v1/file-actions/{action}                    multipart -> 200 | 202  SubmissionResponse
GET    /api/v1/file-actions/{action}/{id}               -> SubmissionResponse (+ Retry-After while running)
POST   /api/v1/file-actions/{action}/{id}/confirm       -> 200 | 202
POST   /api/v1/file-actions/{action}/{id}/cancel        -> 202
GET    /api/v1/file-actions/{action}/{id}/rejects       -> PageResponse<RowRejectResponse>
GET    /api/v1/file-actions/{action}/{id}/error-report   -> the annotated workbook or reject CSV
GET    /api/v1/file-actions/{action}/template            -> a blank workbook built from the binding
```

`SubmissionResponse` nests the platform `OperationResponse` rather than replacing it: a generic client
polls `operation` and ignores the rest, and a client of *this* module reads the row counts, the reject
count and whether somebody has to press **Confirm** - none of which six status constants can express.

**`cancel` answers 202, not 204.** Cancellation is cooperative: the flag is checked between batches, so the
stop has been *requested* and not achieved. Cancelling an already-finished submission returns the envelope
rather than a 409, because the caller's intent is already satisfied and an error would make them handle a
success.

**The template endpoint** is the cheapest usability win available. The commonest reason an import fails is a
heading that does not match, and the headings here are generated from the same binding the reader validates
against - so the two cannot drift apart, which a template maintained by hand in a wiki can and does.

**The base path is fixed, not configurable.** It was a property, and `rest-paths` reads the literal
annotation value - so a value containing a placeholder can never match a path pattern, making the rule
unsatisfiable rather than unsatisfied. The rule was also pointing at something real: a platform starter
whose endpoints move per deployment is one no client can be written against.

## This is the only module that may name `MultipartFile`

`RuleGroup.UPLOADS` in `architecture-rules` fails the build anywhere else. Before this module there was not
one `MultipartFile` in the repository, and the first service to need an upload would have written one - with
the predictable parts written badly: no ceiling before the body is read, `getBytes()`, a DOM workbook read,
and a 400 carrying three thousand row errors. Each of those is a reasonable local decision by somebody with
no reason to know better. The module fixes the state of the code; only the rule stops the next service
repeating it.

The rule's three deliberate blind spots - a reactive upload, a raw `byte[]` body, and how this module itself
behaves - are listed in its own javadoc rather than left to be discovered.

## Configuration

```yaml
ludwig:
  file-action:
    enabled: true
    scanning:
      mode: required                 # required | optional | disabled - fails closed
    storage:
      uploads: s3://orders/file-action/uploads        # no default: see below
      artifacts: s3://orders/file-action/artifacts
      retention: P7D
      retention-interval: PT1H
      retention-batch-size: 100
      retention-lease: PT5M
    inline:
      max-rows: 5000                 # the ceiling an INLINE action may not exceed
    deferred:
      lease: PT5M
      batch-size: 5
      max-attempts: 3
      interval: PT10S
      initial-delay: PT15S
      drain-timeout: PT30S
    defaults:
      max-size: 25MB
      max-rows: 5000                 # matches inline.max-rows, because execution defaults to INLINE
      max-rows-limit: 100000         # the module's declared ceiling; above it, use file-ingest
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
        commit-policy: PER_ROW       # no default; the action does not start without it
        mode: CONFIRM                # DIRECT | CONFIRM | VALIDATE_ONLY
        execution: INLINE            # INLINE | DEFERRED
        required-authority: ORDER_IMPORT
        # every key under defaults may be overridden here
```

`storage.uploads` and `storage.artifacts` have **no defaults**, because the only possible default is a local
directory - which works on a laptop and silently writes a production deployment's uploads to a pod's
ephemeral disk.

`defaults.max-rows` is 5,000 and matches `inline.max-rows`, which is not a coincidence: `execution` defaults
to `INLINE`, so any other value would make every action fail startup until the deployment overrode something.
It was 100,000 against an inline ceiling of 5,000, and a context with a single ordinary action in it would not
start - a starter whose own defaults contradict each other is not configurable, it is broken.

### Everything the application refuses to start for

`FileActionConfigurationValidator`, and every message names the action, the property and the acceptable
values - because a startup failure that does not say what to change is an outage with extra steps:

- a configured action with no `@FileAction` bean, or a bean with no configuration;
- an action with no `commit-policy`;
- a row-level commit policy on a `DocumentHandler`;
- `scanning.mode: required` with no `FileScanner` bean;
- an `INLINE` action above the inline ceiling, or any action above `max-rows-limit`;
- `required-authority` with `security-spring-boot-starter` absent, so nothing would check it;
- a `reject-threshold` outside 0..1, or a non-positive `batch-size`;
- a message code in `FileActionProblemCodes` that does not resolve in both shipped locales;
- a bean annotated `@FileAction` that is not a `FileActionHandler`.

Every problem is reported, not just the first.

## Deferred execution and retention

The submission row **is** the queue, claimed with a lease - the pattern `outbox` and `reconciliation` already
use and the reason `job-core` exists. A message queue would be a second place a submission can be, and the
two can disagree: a message with no row is work nobody can look up, and a row with no message is work that
never happens.

The lease is what makes a pod dying mid-apply recoverable: an instance that stops renewing loses the
submission to the next tick of another, with nobody involved. `max-attempts` is why a submission failing the
same way a fourth time is reported rather than retried for ever.

**The deferred worker deliberately holds no `RunLock`, and the retention job does.** Instances processing
different submissions concurrently is the point of the worker, and `claimForProcessing` is what keeps two off
the same one - a cluster-wide lock there would make a whole deployment's throughput that of one pod. Retention
has no per-item parallelism to gain and several instances deleting the same objects is pure waste, so one
holder at a time is simply correct.

Retention deletes the **objects first**, then marks the row `EXPIRED`. The other order would leave a row
claiming its artifacts are gone while they are still in the bucket - a leak nothing would ever notice; this
order can at worst orphan an object, which shows up in a bucket listing and is logged.

Retention is the module's job and not a bucket lifecycle rule, because a lifecycle rule would delete the
object and leave the submission claiming a result that is no longer there - so a poll would answer
`SUCCEEDED` with a link to nothing, which is exactly what `OperationStatus.EXPIRED` exists to prevent.

## Audit

Through `audit-core`'s single `AuditSink`. `FileActionAuditEvent` is the typed authoring surface and
`toAuditEvent()` is the one place the envelope is assembled; there is no SPI here, no logger named `*.audit`
and no second redaction mask. `record` is **not** wrapped in a `try`/`catch`: whether a sink failure fails the
caller is `AuditFailurePolicy`, resolved from configuration, and a catch in a library would override a
deployment's decision.

What is recorded: the filename, the content hash, the size, the counts, the scanner's answer and the outcome.
**Not any cell of the file.** An audit trail is read by operators and kept longer than the file is, and a row
of somebody's order data in it would be a copy of personal data with a different retention policy from the
file it came from. The hash is what links an entry to the stored object without duplicating its contents - the
same reasoning `idempotency`'s `RequestFingerprint` gives.

## Data access

QueryDSL against generated Q-types only. **This change introduces no third SQL carve-out** - the two that
exist are `ru.ludwigandreas.ingest.bulk` and `ru.ludwigandreas.idempotency.sql` - and `NoSqlStringsTest` fails
this module's build if one appears. The claim is written through the ORM precisely so the `@Version` column is
honoured, which is what stops a concurrent cancel being lost.

Two tables, and deliberately not three: there is no shared operation table, per the `long-running-operations`
capability, so this module keeps its own submission row and maps onto the envelope at its edge.

## What is deliberately not here

- **No resumable checkpoint.** Nothing resumes mid-file; a request a person is waiting for is re-submitted.
- **No staging table and no `MERGE`.** The rows become a domain call, not a bulk insert.
- **No `.xls`, `.ods`, SpreadsheetML, HTML tables, PDF, or an archive of files.** The allow-list is closed and
  each absence has a reason in `SourceFormat`.
- **No scanner implementation.** The seam, and a startup failure if you have not chosen.
- **No UI.** Endpoints and a generated template, not a drag-and-drop widget.
- **No reuse of `export`'s `ReportWriter`.** It is bound to `ReportDefinition`, `WriterContext`, parameter
  records and role-filtered column sets, and a reject report is none of those; depending on that module would
  pull its engine and eleven optional dependencies in to reuse one `SXSSFWorkbook` wrapper. The duplication is
  deliberate and recorded at the writer.

## What cannot be enforced, and is written down instead

Four things, listed here and in [`docs/harness-enforcement.md`](../../docs/harness-enforcement.md) rather than
quietly omitted:

1. **A `RowHandler` must be idempotent per row.** A deferred submission is claimed under a lease; if the pod
   dies after the transaction carrying rows 501-1000 committed but before progress was recorded, another
   instance re-applies those rows. Nothing in bytecode can see whether a write is conditional. Stated on
   `RowHandler.apply`.
2. **A `DocumentHandler` must not call a partner inside the apply transaction.** One transaction covers every
   row; a partner call inside it holds a database connection for the partner's latency, and if the transaction
   rolls back the partner has still been called. Stated on `DocumentHandler.apply`.
3. **Whether a declared `commit-policy` is the right one for the domain.** Startup validation forces the
   declaration; only a person can judge it.
4. **Whether a deployment's `FileScanner` actually scans.** `scanning.mode` forces the choice to be made; it
   cannot verify the implementation.
